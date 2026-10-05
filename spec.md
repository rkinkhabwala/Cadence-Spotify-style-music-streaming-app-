# spec.md — Cadence (Spotify-style music streaming app)

> Working name: **Cadence**. Java 21 / Spring Boot 3 backend, built with Maven as a modular monolith. Local-only portfolio project (everything runs in Docker Compose).

---

## 1. Overview

Cadence lets users sign up, browse and search a music catalog, play tracks with seeking and adaptive bitrate, build playlists, like tracks, and see listening history and recommendations. Admins manage the catalog and upload audio, which is transcoded asynchronously into HLS for streaming. Recommendations come from the separate real-time recommendation system, which Cadence feeds with activity events.

### Goals
- Real, working playback with seek and adaptive bitrate (HLS)
- Clean bounded contexts: Identity, Catalog, Library, Streaming, Search, Activity
- Event-driven activity tracking (Kafka) that recommendations can consume
- Production-style engineering: migrations, tests with Testcontainers, observability, Docker Compose for local dev

### Non-goals (for now)
- DRM / encrypted streams
- Payments and real subscription billing (Premium is a flag only)
- Native mobile apps (web client only; API is mobile-ready)
- Artist self-service (no artist accounts in v1; catalog is admin-managed)
- Cloud deployment (AWS, Kubernetes, CDN) — runs locally only
- Building a recommendation engine inside Cadence (reuses the existing recommender)
- Licensing / royalty accounting
- Podcasts, lyrics, social feed

---

## 2. Tech stack

| Concern | Choice |
|---|---|
| Language / runtime | Java 21 (virtual threads enabled) |
| Framework | Spring Boot 3.x (Web, Security, Data JPA, Validation, Actuator) |
| Build | Maven multi-module with Maven Wrapper (`./mvnw`), parent POM importing the Spring Boot BOM |
| Database | PostgreSQL 16, Flyway migrations |
| Cache / sessions / rate limits | Redis 7 |
| Object storage | MinIO (S3-compatible API via AWS SDK v2) |
| Messaging | Apache Kafka (KRaft mode), Spring Kafka |
| Search | Elasticsearch 8 (or OpenSearch) |
| Transcoding | FFmpeg invoked from a worker module |
| Auth | Spring Security, JWT access tokens (15 min) + rotating refresh tokens (30 days) |
| API docs | springdoc-openapi (Swagger UI) |
| Mapping | MapStruct |
| Testing | JUnit 5, AssertJ, Mockito, Testcontainers, Spring REST Docs or RestAssured |
| Observability | Micrometer + Prometheus, OpenTelemetry tracing, structured JSON logs |
| Frontend (Phase 2) | React + TypeScript + Vite + hls.js |
| Recommendations (Phase 3) | Existing real-time recommendation system, integrated over Kafka + HTTP |
| Infra | Docker Compose only |

---

## 3. Architecture

### 3.1 Modular monolith layout

One deployable (`cadence-api`) plus one worker (`cadence-transcoder`). Each module owns its tables and exposes only a public API package; modules talk to each other through interfaces or domain events, never by reaching into another module's repositories. Enforce boundaries with **Spring Modulith** (or ArchUnit tests).

```
cadence/
├── pom.xml                  # parent POM: <modules>, dependencyManagement, plugin config
├── mvnw, .mvn/
├── docker-compose.yml
├── cadence-api/
│   ├── pom.xml
│   └── src/main/java/com/cadence/
│       ├── CadenceApplication.java
│       ├── common/          # errors, pagination, security utils, outbox
│       ├── identity/        # users, auth, roles, refresh tokens
│       ├── catalog/         # artists, albums, tracks, genres, admin uploads
│       ├── library/         # playlists, likes, follows
│       ├── streaming/       # playback, signed URLs, manifests
│       ├── search/          # ES indexing + query API
│       └── activity/        # play events, history, recommender client
├── cadence-transcoder/      # pom.xml; Kafka consumer → FFmpeg → MinIO
├── cadence-events/          # pom.xml; shared event records (JSON schema), used by api, transcoder and the recommender
└── cadence-web/             # React client (Phase 2; built with npm, optionally via frontend-maven-plugin)
```

Each module follows: `api/` (controllers + DTOs), `application/` (services, use cases), `domain/` (entities, value objects, events), `infrastructure/` (repositories, clients).

### 3.2 Request flow — playback
1. Client calls `POST /api/v1/playback/{trackId}` with JWT.
2. Streaming module checks track is `READY` and user may play it (Premium-only bitrate rules).
3. Returns a short-lived signed URL (5 min) to the HLS master manifest.
4. Client (hls.js) fetches manifest and segments directly from MinIO using the presigned URLs. (Optional: an nginx caching proxy in Compose to mimic a CDN.)
5. Client sends `POST /api/v1/activity/plays` at 30 seconds of playback (counts as a "stream") and on completion/skip.

### 3.3 Upload and transcoding flow
1. Admin calls `POST /api/v1/admin/tracks/{id}/upload-url` → gets presigned MinIO PUT URL for the raw file.
2. Client uploads directly to `raw/{trackId}/source.{ext}`.
3. Admin client calls `POST /api/v1/admin/tracks/{id}/upload-complete`; track status → `PROCESSING`; `TrackUploaded` event written to outbox → Kafka topic `catalog.track-uploaded`.
4. Transcoder consumes the event, runs FFmpeg to produce AAC HLS renditions at 96 / 160 / 320 kbps with 10-second segments, a master `.m3u8`, and extracts duration + loudness (EBU R128).
5. Uploads output to `hls/{trackId}/...`, publishes `streaming.track-transcoded` (or `...-failed`).
6. Catalog consumes it, sets status `READY` (or `FAILED`) and stores duration.

### 3.4 Events (Kafka topics)

| Topic | Producer | Consumers | Key |
|---|---|---|---|
| `catalog.track-uploaded` | catalog | transcoder | trackId |
| `streaming.track-transcoded` | transcoder | catalog | trackId |
| `activity.track-played` | activity | history, play counts, **recommender** | userId |
| `library.track-liked` | library | **recommender** | userId |
| `library.artist-followed` | library | **recommender** | userId |
| `catalog.entity-changed` | catalog | search indexer, **recommender** (item metadata) | entityId |

Use the **transactional outbox pattern** (outbox table + poller or Debezium) so DB writes and events never diverge. Consumers must be idempotent (store processed event IDs).

### 3.5 Recommender integration (Phase 3)

Cadence does **not** compute recommendations. It reuses the existing real-time recommendation system:

- **Inbound to recommender:** the recommender subscribes to the Kafka topics marked above. Every event uses a shared envelope from `cadence-events`:
  ```json
  {
    "eventId": "uuid", "eventType": "track-played", "occurredAt": "2026-10-05T15:42:00Z",
    "userId": "uuid", "itemType": "song", "itemId": "uuid",
    "payload": { "msPlayed": 182000, "completed": true, "skipped": false, "source": "PLAYLIST" }
  }
  ```
  `itemType` is always `song` (or `artist`/`album` for follow and catalog events) so the payload fits the recommender's multi-domain model (books, videos, posts, songs).
- **Outbound from recommender:** Cadence calls the recommender's HTTP API, e.g. `GET /recommendations?userId=&domain=song&limit=30`, through a `RecommenderClient` (Spring `RestClient`) with a 300 ms timeout and Resilience4j circuit breaker.
- **Hydration and filtering:** Cadence hydrates returned item IDs from its catalog, drops tracks that aren't `READY` or are already liked, and caches results per user in Redis for 10 minutes.
- **Fallback:** if the recommender is down or returns nothing, serve popular tracks (TrackStats) from genres the user plays most.
- **Shared Compose network:** the recommender runs as its own container on the same Docker network (`cadence-net`), so the two projects can be started together.
- The exact endpoint and event schema must be aligned with the recommender's spec.md before Phase 3 starts.

---

## 4. Domain model

### identity
- **User**: id (UUID), email (unique), passwordHash (BCrypt), displayName, avatarUrl, country, birthDate, plan (`FREE` | `PREMIUM`), roles (`LISTENER`, `ADMIN`), createdAt, updatedAt
- Admin accounts are created only by the seed script / a bootstrap env var (`CADENCE_ADMIN_EMAIL`); public registration always yields `LISTENER`.
- **RefreshToken**: id, userId, tokenHash, expiresAt, revokedAt, replacedBy

### catalog
- **Artist**: id, name, bio, imageUrl, verified, monthlyListeners (denormalized)
- **Album**: id, title, artistId, releaseDate, type (`ALBUM` | `SINGLE` | `EP`), coverUrl, label
- **Track**: id, title, albumId, trackNumber, discNumber, durationMs, explicit, status (`DRAFT` | `PROCESSING` | `READY` | `FAILED`), playCount, isrc
- **TrackArtist**: trackId, artistId, role (`PRIMARY` | `FEATURED`)
- **Genre**: id, name; **AlbumGenre** join

### library
- **Playlist**: id, ownerId, name, description, coverUrl, visibility (`PUBLIC` | `PRIVATE`), collaborative, version (optimistic locking), createdAt, updatedAt
- **PlaylistTrack**: playlistId, trackId, position (use fractional/lexorank ordering to avoid renumbering), addedBy, addedAt
- **LikedTrack**: userId, trackId, likedAt
- **FollowedArtist**: userId, artistId, followedAt
- **SavedAlbum**: userId, albumId, savedAt

### activity
- **PlayEvent**: id, userId, trackId, startedAt, msPlayed, source (`PLAYLIST` | `ALBUM` | `SEARCH` | `RADIO`), sourceId, completed, skipped
- **ListeningHistory** (read model): userId, trackId, lastPlayedAt
- **TrackStats** (read model): trackId, plays30d, uniqueListeners30d

### Key constraints
- Playlist max 10,000 tracks.
- A track is playable only when status = `READY`.
- A play counts toward `playCount` only when `msPlayed >= 30000`.
- Free users: max 160 kbps rendition, 6 skips/hour (Phase 3).

---

## 5. API contract (v1)

Base path `/api/v1`. JSON. Cursor pagination: `?limit=20&cursor=...` → `{ "items": [...], "nextCursor": "..." }`. Errors follow RFC 7807 Problem Details.

### Auth
| Method | Path | Notes |
|---|---|---|
| POST | `/auth/register` | email, password, displayName → 201 + tokens |
| POST | `/auth/login` | → `{ accessToken, refreshToken, expiresIn }` |
| POST | `/auth/refresh` | rotates refresh token; reuse of an old token revokes the family |
| POST | `/auth/logout` | revokes refresh token |
| GET | `/me` | current profile |
| PATCH | `/me` | update displayName, avatar, country |

### Catalog
| Method | Path | Notes |
|---|---|---|
| GET | `/artists/{id}` | artist + top 10 tracks |
| GET | `/artists/{id}/albums` | paginated |
| GET | `/albums/{id}` | album + tracklist |
| GET | `/tracks/{id}` | track detail |
| GET | `/genres` | list |

### Admin (role `ADMIN` only — all catalog writes live here)
| Method | Path | Notes |
|---|---|---|
| POST / PATCH / DELETE | `/admin/artists[/{id}]` | manage artists |
| POST / PATCH / DELETE | `/admin/albums[/{id}]` | manage albums |
| POST / PATCH / DELETE | `/admin/tracks[/{id}]` | POST creates a DRAFT track |
| POST | `/admin/tracks/{id}/upload-url` | presigned PUT URL |
| POST | `/admin/tracks/{id}/upload-complete` | triggers transcoding |
| POST | `/admin/tracks/{id}/retranscode` | retry a FAILED track |
| GET | `/admin/tracks?status=FAILED` | processing dashboard |

### Streaming
| Method | Path | Notes |
|---|---|---|
| POST | `/playback/{trackId}` | → `{ manifestUrl, expiresAt, durationMs }` |
| GET | `/tracks/{id}/stream` | Phase 1 fallback: HTTP Range (206) on a single file |

### Library
| Method | Path | Notes |
|---|---|---|
| GET | `/me/playlists` | own + followed |
| POST | `/playlists` | create |
| GET | `/playlists/{id}` | with paginated tracks |
| PATCH | `/playlists/{id}` | rename, description, visibility (If-Match version) |
| DELETE | `/playlists/{id}` | owner only |
| POST | `/playlists/{id}/tracks` | `{ trackIds: [], position? }` |
| DELETE | `/playlists/{id}/tracks` | `{ trackIds: [] }` |
| PUT | `/playlists/{id}/tracks/reorder` | `{ trackId, afterTrackId }` |
| PUT / DELETE | `/me/likes/tracks/{trackId}` | like / unlike (idempotent) |
| GET | `/me/likes/tracks` | paginated |
| PUT / DELETE | `/me/following/artists/{artistId}` | follow / unfollow |

### Search
| Method | Path | Notes |
|---|---|---|
| GET | `/search?q=&types=track,artist,album,playlist&limit=` | fuzzy, prefix, grouped by type |
| GET | `/search/suggest?q=` | search-as-you-type, under 100 ms p95 |

### Activity and recommendations
| Method | Path | Notes |
|---|---|---|
| POST | `/activity/plays` | `{ trackId, msPlayed, source, sourceId, completed, skipped }` |
| GET | `/me/recently-played` | last 50 |
| GET | `/me/top/tracks?range=short\|medium\|long` | 4 weeks / 6 months / all time |
| GET | `/me/recommendations` | Phase 3; proxied from the recommender (see 3.5) |
| GET | `/home` | shelves: recently played, made for you, new releases |

---

## 6. Security

- BCrypt password hashing (strength 12); password rules: min 10 chars.
- JWT signed with RS256; public key exposed at `/.well-known/jwks.json` so services can validate later.
- Refresh token rotation with reuse detection; refresh tokens stored hashed.
- Role and ownership checks via `@PreAuthorize` plus domain-level checks (e.g. only playlist owner or collaborators edit). Everything under `/admin/**` requires `ADMIN`.
- Presigned MinIO URLs only; buckets are private.
- Rate limiting with Bucket4j + Redis: login 5/min/IP, search 30/s/user.
- Input validation with Bean Validation on every DTO; uploads limited to 200 MB, MIME-checked (mp3, flac, wav, m4a).
- CORS restricted to the web client origin.

---

## 7. Non-functional requirements

| Area | Target |
|---|---|
| Playback start (manifest issued) | under 200 ms p95 server-side |
| Catalog reads | under 150 ms p95; artist/album pages cached in Redis (TTL 10 min, evicted on change) |
| Search suggest | under 100 ms p95 |
| Transcoding | 4-minute track ready in under 60 s on a dev laptop |
| Footprint | whole stack runs on one laptop (16 GB RAM) via `docker compose up`; API is stateless |
| Data integrity | outbox for all cross-module events; idempotent consumers |
| Observability | `/actuator/health`, `/actuator/prometheus`, trace IDs in every log line |
| Test coverage | 80%+ on application/domain layers; every endpoint has an integration test |

---

## 8. Local development

`docker-compose.yml` runs: postgres, redis, kafka (KRaft), elasticsearch, minio (+ bucket init), kafka-ui, prometheus, grafana.

- `./mvnw -pl cadence-api spring-boot:run` for the API; `./mvnw -pl cadence-transcoder spring-boot:run` for the worker. Optional Compose profile `app` runs both as containers.
- Seed script creates the admin user and loads 5 artists, 10 albums, 50 royalty-free tracks (e.g. CC-licensed from the Free Music Archive) through the admin upload flow, so the app is usable immediately.
- `make up` / `make down` / `make seed` convenience targets.
- `.env.example` documents every config value; no secrets committed.

---

## 9. Phases

### Phase 1 — Core backend and playback (MVP)
**Scope**
- Project skeleton, multi-module build, Docker Compose, Flyway baseline
- identity: register, login, refresh, logout, `/me`
- catalog: public read APIs; admin-only artist / album / track management and presigned uploads
- transcoder: Kafka consumer, FFmpeg → HLS, status updates
- streaming: signed manifest URLs; Range-request fallback endpoint
- library: playlists (CRUD, add/remove/reorder), likes, follows
- Outbox + Kafka wiring, global error handling, OpenAPI docs
- Seed data

**Acceptance criteria**
- A new user can register, log in, and receive tokens; expired access token + valid refresh token yields a new pair.
- An admin uploads an MP3; within 60 s the track is `READY` and has three HLS renditions in MinIO.
- A `LISTENER` calling any `/admin/**` endpoint gets 403.
- `POST /playback/{id}` returns a manifest URL that plays (and seeks) in a test hls.js page.
- A user can create a playlist, add 3 tracks, reorder them, and see the new order persisted.
- Like/unlike and follow/unfollow are idempotent.
- All endpoints covered by Testcontainers integration tests; `./mvnw verify` is green.

### Phase 2 — Search, activity, and web client
**Scope**
- Elasticsearch indexing via `catalog.entity-changed`; `/search` and `/search/suggest`
- Play event ingestion, recently played, top tracks, play counts (30 s rule)
- Home endpoint with shelves
- React web client: login, home, search, artist/album/playlist pages, persistent bottom player bar with queue, shuffle, repeat
- Simple admin pages: create artist/album/track, upload audio, view processing status
- Redis caching for catalog reads

**Acceptance criteria**
- Typing "beatls" returns the correct artist in suggestions (fuzzy match).
- A catalog edit is reflected in search within 5 seconds.
- Playing a track for 30 s increments its play count exactly once even if the event is delivered twice.
- Recently played shows the last 50 distinct tracks in order.
- Web player continues playback across page navigation.

### Phase 3 — Recommender integration and Premium rules
**Scope**
- Align event envelope and recommendation API with the existing recommender's spec.md
- Publish `track-played`, `track-liked`, `artist-followed`, and catalog-change events in the shared envelope
- `RecommenderClient` with timeout, circuit breaker, Redis cache, and popularity fallback
- `/me/recommendations` plus "Made for you" and "Because you listened to X" shelves on `/home`, all sourced from the recommender
- Free vs Premium: bitrate cap, skip limit, ad-slot placeholder
- Collaborative playlists
- Rate limiting, Grafana dashboards, load test with Gatling (target: 500 concurrent listeners locally)

**Acceptance criteria**
- With both stacks running, a play event in Cadence is visible to the recommender within 2 seconds.
- A user with 20+ plays gets at least 20 recommendations from the recommender, none already liked and all `READY`.
- Stopping the recommender container still returns recommendations (fallback) with no 5xx from Cadence.
- A free user cannot obtain the 320 kbps rendition; a 7th skip within an hour returns 429.
- Two collaborators editing the same playlist concurrently do not lose writes (optimistic locking + retry).
- Gatling run completes with under 1% errors and p95 playback-start under 200 ms.

### Phase 4 — Optional / stretch
- Split `streaming` and `activity` into separate services behind Spring Cloud Gateway (still in Compose)
- Offline downloads (encrypted cache, Premium only)
- Social: friend activity feed, shared listening sessions over WebSocket
- CI on GitHub Actions (build + Testcontainers tests)

---

## 10. Testing strategy

- **Unit**: domain logic (playlist ordering, skip limits, token rotation) with no Spring context.
- **Slice**: `@WebMvcTest` for controllers, `@DataJpaTest` with Testcontainers Postgres for repositories.
- **Integration**: full context with Testcontainers (Postgres, Kafka, Redis, MinIO, Elasticsearch); one happy path + key failure paths per endpoint.
- **Contract**: OpenAPI spec generated in CI and diffed to catch breaking changes.
- **Architecture**: Spring Modulith / ArchUnit tests verify no cross-module repository access.
- **Load**: Gatling scenarios for login → search → play → like.

---

## 11. Coding conventions

- Records for DTOs and value objects; entities stay in `domain/`, never returned from controllers.
- Constructor injection only; no field `@Autowired`.
- Every write endpoint is idempotent or documents why not.
- UUIDv7 for IDs (time-ordered, index-friendly).
- Timestamps in UTC (`Instant`); Flyway migrations are never edited once merged.
- Conventional commits; one PR per feature with tests.

---

## 12. Decisions

| # | Question | Decision |
|---|---|---|
| 1 | Build tool | **Maven** multi-module with wrapper |
| 2 | Who uploads | **Admin only** for v1; no artist accounts |
| 3 | Web client timing | **React web client in Phase 2**; Phase 1 uses a minimal hls.js test page for verification |
| 4 | Deployment | **Local only** (Docker Compose); no cloud, Kubernetes, or CDN |
| 5 | Recommendations | **Reuse the existing real-time recommendation system** via Kafka events + HTTP API (section 3.5) |