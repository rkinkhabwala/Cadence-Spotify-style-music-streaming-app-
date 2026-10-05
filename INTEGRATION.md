# INTEGRATION.md — Cadence ↔ recommender

This is the contract between Cadence and the real-time recommender (spec 3.5): what Cadence already provides, and
**exactly what the recommender still has to expose** before the two can be switched on together. The recommender
project was read, not modified (`~/Desktop/Recommendation engine`, commit `5e4874e`, read 2026-10-05). The final
contract is recorded in DECISIONS.md D88.

**Status:** Cadence's side is built and tested against a WireMock stub of the recommender's real HTTP contract
(`RecommendationIT`). Cadence runs with `CADENCE_RECOMMENDER_ENABLED=false`, so every recommendation comes from the
fallback (popular tracks in the user's top genres) until the gaps below are closed.

## 1. What already fits

### Cadence → recommender: `GET /v1/recommendations` (no change needed)

Cadence's `RecommenderClient` calls the recommender's existing serving API exactly as its `RecommendationController`
defines it:

```
GET {CADENCE_RECOMMENDER_BASE_URL}/v1/recommendations
      ?userId=<Cadence user UUID>&domain=song&context=home|radio&limit=50[&seedItemId=<track UUID>]
X-Api-Key: <CADENCE_RECOMMENDER_API_KEY>
```

- `context=home` feeds "Made for you" and `GET /me/recommendations`. `context=radio&seedItemId=<last played track>`
  feeds "Because you listened to X". Both surfaces are in the recommender's `SURFACES`.
- Cadence reads `recommendationId` and `items[].{itemId, position, reasonCode}`. It ignores the other fields and any
  `itemId` that isn't a UUID.
- Cadence user and track ids are UUID strings (36 chars), which match the recommender's
  `^[A-Za-z0-9_.:-]{1,64}$`. In api-key mode the caller is a service principal, so `Principals.requireUser`
  lets Cadence act for any user.
- Client behaviour (D89):
  - **300 ms** connect and read timeouts, and **one attempt** (`max-attempts: 1`).
  - A 429/503 from the recommender's `AdmissionFilter` (with `Retry-After: 1`) goes straight to the fallback, with no
    retry and no waiting.
  - A circuit breaker opens after ≥ 50 % failures or slow calls in a window of 20 calls (minimum 5), for 30 s.
  - Answers are cached per user/context/seed for 10 minutes.
  - Cadence over-fetches 50 items, then drops tracks that aren't READY or that the user already likes.

### Recommender → Cadence ids

The recommender must use Cadence ids unchanged: `itemId` = Cadence track id, `artistId` = Cadence artist id,
`userId` = Cadence user id. Recommendations for ids Cadence doesn't know are silently dropped.

## 2. What the recommender must add

### G1 — A bridge that consumes Cadence's Kafka topics (required)

Spec 3.4/3.5 says the recommender subscribes to Cadence's topics. Today it only ingests over HTTP (`POST /v1/events`
→ Avro `events.raw.v1`) and from its own catalog service. It needs a consumer, for example a small `cadence-bridge`
service or a profile of `ingestion-api`, that:

| Setting | Value |
|---|---|
| Bootstrap servers | Cadence's Kafka: `kafka:29092` on the `cadence-net` network (the host listener is `localhost:9092`) |
| Topics | `activity.track-played`, `library.track-liked`, `library.artist-followed`, `catalog.entity-changed` |
| Consumer group | e.g. `recommender.cadence-bridge` (Cadence never reads it) |
| Value format | UTF-8 JSON: Cadence's `EventEnvelope` (`cadence-events/src/main/resources/schemas/event-envelope.schema.json`) |
| Keys | user id for the activity/library topics (per-user order), entity id for `catalog.entity-changed` |
| Delivery | at least once (transactional outbox); deduplicate on `eventId`, which is a UUIDv7 and fits the recommender's `event_id` |
| Mapping | `com.cadence.events.recommender.RecommenderMapping` in `cadence-events`, the module spec 3.1 shares with the recommender. It produces the recommender's own `EventDto` and `CatalogItemDto` JSON (table below) |
| Output | produce `UserEvent` to `events.raw.v1` exactly as `ingestion-api` does, and upsert/delete catalog items as `catalog-service` does (`POST /v1/catalog/items`, `DELETE /v1/catalog/items/{id}`, with `catalog:write`) |

Freshness target (spec 9 Phase 3 AC1): a Cadence play must be visible to the recommender **within 2 s**. Cadence
already publishes within that bound: the outbox is polled every 500 ms, and `RecommenderContractIT` measures
POST → Kafka at < 2 s. The bridge then adds its own consume-and-produce time.

**Event mapping** (implemented and unit-tested in `RecommenderMappingTest`, which checks the recommender's
`EventValidator` rules):

| Cadence envelope | Recommender `EventDto` |
|---|---|
| `eventId`, `userId`, `itemId` | `eventId`, `userId`, `itemId` (as strings) |
| `occurredAt` | `eventTs` |
| — | `domain = "song"` |
| `track-played`, first report (30 s, neither completed nor skipped) | `eventType = "play_start"` (not weighted, so a playback is never counted twice) |
| `track-played` with `completed` | `eventType = "play_end"`, `value` = seconds listened |
| `track-played` with `skipped` | `eventType = "skip"`, `value` = seconds listened, `media.positionMs` = ms listened |
| `payload.durationMs` | `media.durationMs` (completion %) |
| `payload.sessionId` (the web client's per-tab session) | `sessionId`; if absent, `"play-" + playId` |
| `payload.recommendationId`, `payload.position` | `recommendationId`, `position` (attribution of served lists) |
| `payload.source` | `context.surface` (lower case), or `"home"` when played from a recommendation |
| `track-liked` | `eventType = "like"`, `sessionId = eventId` |
| `artist-followed` (`itemType = artist`) | `eventType = "follow"`, `itemId` = **artist** id (see G3) |
| `track-unliked`, `artist-unfollowed` | no equivalent yet (see G2) |
| `catalog.entity-changed`, `itemType = song`, READY snapshot | catalog upsert: `itemId`, `title`, `artistId`/`artistName` = PRIMARY credit, `genres` (album genres, lower case), `durationMs`, `releaseDate`, `explicit` |
| same, DELETED or not READY | catalog delete of `itemId` |
| `catalog.entity-changed` for artists/albums | ignored: Cadence re-emits every affected track with fresh names and genres |

### G2 — Retractions: UNLIKE and UNFOLLOW (required for correct taste)

Cadence emits `track-unliked` and `artist-unfollowed` (D51). The recommender's `EventType` enum has no way to retract
a LIKE or a FOLLOW. Add `UNLIKE` and `UNFOLLOW` symbols (BACKWARD-compatible thanks to the enum default), and handle
them in `UserFeatureProcessor` by subtracting the earlier contribution. Until then, the bridge drops these events, so
an unliked track keeps its positive weight (it decays with the normal half-lives).

### G3 — Artist-level FOLLOW (required for follows to count)

`UserFeatureProcessor` applies `followArtistWeight` to the **item's** artist (`p.artistId()` of the event's
`itemId`). Cadence follows artists, not tracks, so the bridge sends `follow` with `itemId` = the artist id. That
resolves to no item profile and is ignored today. The recommender should accept an artist-level FOLLOW, either with a
new optional `creatorId` field on `UserEvent` or by treating an `itemId` that is a known `artist_id` as one, and boost
`artistAff[artistId]` directly.

### G4 — Join the `cadence-net` network and avoid port clashes (required to run both stacks)

- Attach `recommendation-api` (and the bridge) to Cadence's network:
  `networks: { cadence-net: { external: true } }`.
  Cadence's api container calls `http://recommendation-api:8080` (`CADENCE_RECOMMENDER_INTERNAL_URL`).
- Both stacks publish host ports **8080** (Cadence API vs recommendation-api), **3000** (Cadence web vs Grafana) and
  **9090** (Prometheus). Remap the recommender's host ports when both run, e.g. recommendation-api → `18080` (Cadence's
  default `CADENCE_RECOMMENDER_BASE_URL` for host runs) and Grafana → `3001`.
- Kafka: the recommender keeps its own cluster for its Avro topics. Only the bridge connects to Cadence's
  `kafka:29092` as a second bootstrap.

### G5 — Domain and cold start (no change needed, noted for completeness)

- `song` is enabled by default (`recs.api.enabled-domains`).
- New Cadence users have no events. The recommender then serves `ANONYMOUS`/trending lists, which Cadence shows
  as-is.
- Onboarding (`POST /v1/users/{id}/onboarding`) is not used by Cadence.

## 3. Switching it on

1. Close G1–G4 in the recommender project, then start it on `cadence-net`.
2. In Cadence's `.env`: `CADENCE_RECOMMENDER_ENABLED=true`, `CADENCE_RECOMMENDER_API_KEY=<one of RECS_API_KEYS>`, and
   `CADENCE_RECOMMENDER_BASE_URL=http://localhost:18080` when the API runs on the host.
3. Seed the recommender's catalog from Cadence. `POST /api/v1/admin/search/reindex` replays every catalog entity
   through `catalog.entity-changed` (D64), and the bridge turns the READY tracks into catalog items.
4. Check:
   - `GET /api/v1/me/recommendations` returns `"source": "recommender"`.
   - `cadence_recommender_requests_seconds_count{outcome="success"}` increases.
   - Stopping the recommender container switches responses to `"source": "fallback"` with no 5xx (spec 9 Phase 3 AC3).
