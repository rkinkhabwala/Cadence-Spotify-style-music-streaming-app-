# Cadence

A Spotify-style music streaming app: a Java 21 / Spring Boot 3.5 modular monolith (`cadence-api`), a Kafka +
FFmpeg transcoding worker (`cadence-transcoder`), shared event contracts (`cadence-events`) and a React web client
(`cadence-web`). Everything runs
locally in Docker Compose. See [`spec.md`](spec.md) for the product spec, [`DECISIONS.md`](DECISIONS.md) for
assumptions, [`PROGRESS.md`](PROGRESS.md) for what has been built, and [`INTEGRATION.md`](INTEGRATION.md) for the
contract with the external recommender.

## Status

**Phase 1 (core backend and playback) is complete** (slices 1.1–1.6). **Phase 2 (search, activity, web client) is complete**: slices 2.1–2.3. **Phase 3** is in progress: slice 3.1 (recommender integration) is done. What works now:

- Multi-module Maven build (`./mvnw`), JDK 21, virtual threads.
- `cadence-api` boots. It has Flyway (outbox + processed-event tables), `/actuator/health`, Swagger UI,
  RFC 7807 errors, cursor pagination helpers and UUIDv7 IDs. A transactional outbox polls into Kafka and
  creates every topic from spec 3.4 at startup.
- `cadence-transcoder` boots and consumes `catalog.track-uploaded`. It's a stub that only logs; FFmpeg comes in slice 1.4.
- `cadence-events` holds the shared `EventEnvelope` (spec 3.5), topic names, its JSON Schema and the Jackson settings.
- **Identity:** register, login (rate-limited to 5 per minute per IP), refresh-token rotation with reuse
  detection (the refresh token is an HttpOnly, SameSite=Strict cookie, never in a JSON body or Web Storage), logout, `GET/PATCH /api/v1/me`, RS256 JWTs (15 min) with public keys at `/.well-known/jwks.json`.
  The admin is created at startup from `CADENCE_ADMIN_EMAIL`/`CADENCE_ADMIN_PASSWORD`, and every `/api/v1/admin/**` route requires ADMIN.
- **Catalog:** public reads (`/artists/{id}` with top tracks, `/artists/{id}/albums`, `/albums/{id}`, `/tracks/{id}`,
  `/genres`) showing READY tracks only. Admin CRUD for artists, albums and tracks, and the upload flow: `upload-url`
  (presigned PUT straight to MinIO, ≤ 200 MB) → `upload-complete` (size and magic-byte check, then PROCESSING and a
  `catalog.track-uploaded` event via the outbox) → `retranscode`, plus the `GET /admin/tracks?status=` dashboard.
  Every catalog change emits `catalog.entity-changed`.
- **Transcoding:** `cadence-transcoder` turns each upload into AAC HLS at 96/160/320 kbps (10 s segments and a master
  playlist), plus a 160 kbps single file. It measures duration and EBU R128 loudness, and the track becomes READY (or
  FAILED with a reason). A 3-minute MP3 is READY in about 15 s.
- **Playback:** `POST /api/v1/playback/{trackId}` → `{manifestUrl, expiresAt, durationMs}`. The API serves the manifests
  (free users only see 96/160 kbps) with presigned MinIO segment URLs. `GET /api/v1/tracks/{id}/stream` serves HTTP Range
  requests. A test player lives at http://localhost:8080/dev/player.html (dev profile).
- **Library:** playlists (create, rename with `If-Match`, delete; add at a position, remove, reorder with fractional
  ordering; up to 10,000 tracks; private by default), plus idempotent likes, follows and saved albums. Likes and
  follows emit `library.*` events for the recommender.
- **Search:** Elasticsearch indices of artists, albums, READY tracks and public playlists, kept current from
  `catalog.entity-changed` / `library.playlist-changed` events (edits are searchable within about a second).
  `GET /api/v1/search?q=&types=&limit=&cursor=` is fuzzy and prefix-aware and grouped by type;
  `GET /api/v1/search/suggest?q=` gives search-as-you-type suggestions ("beatls" finds "The Beatlz"). Both are
  rate-limited to 30/s per user. `POST /api/v1/admin/search/reindex` rebuilds the indices from a full replay.
- **Activity:** `POST /api/v1/activity/plays` takes playback reports (at 30 s and on completion/skip, one `playId` per
  playback; idempotent). A play counts toward the track's play count exactly once, when it reaches 30 s.
  `GET /api/v1/me/recently-played` (last 50 distinct tracks), `GET /api/v1/me/top/tracks?range=short|medium|long`, and
  `GET /api/v1/home` with shelves: recently played, made for you, because you listened to X, your top tracks, popular
  right now (30-day stats), new releases.
- **Recommendations:** `GET /api/v1/me/recommendations` and the two recommendation shelves come from the external
  recommender's `GET /v1/recommendations`. The client has a 300 ms timeout, no hidden retries and a circuit breaker;
  answers are cached in Redis for 10 minutes and filtered to READY tracks the user hasn't liked. When the recommender
  is off or down, popular tracks from the user's top genres are served instead (`"source": "fallback"`). The
  recommender doesn't consume Cadence's topics yet, so it is **off by default** (`CADENCE_RECOMMENDER_ENABLED`).
  [`INTEGRATION.md`](INTEGRATION.md) lists what it has to add.
- **Web client** (`cadence-web`, React + TypeScript + Vite + hls.js): log in / sign up, home shelves, search with
  as-you-type suggestions, artist / album / playlist pages, Liked Songs, your library, top tracks, and a persistent
  player bar with queue, shuffle, repeat, seek, volume and OS media keys. Playback never stops on page navigation.
  Admins get a catalog page: create artists, albums and tracks, upload audio with progress, and watch transcoding.
- **Caching:** artist and album pages are cached in Redis for 10 minutes and cleared on every catalog change.
- **Seed data:** `make seed` loads 5 artists, 10 albums and 20 tracks through the real upload flow, plus a demo listener.

## Prerequisites

- **JDK 21** installed (e.g. `brew install openjdk@21`). Run `make toolchains` once per machine: it registers
  JDK 21 in `~/.m2/toolchains.xml`, and from then on `./mvnw` compiles and tests on JDK 21 whatever your shell's
  `java` is. With [direnv](https://direnv.net), `direnv allow` also puts JDK 21 on your `PATH` via `.envrc`.
- **Docker** (Docker Desktop or compatible), for Compose and Testcontainers.
- **FFmpeg** on the `PATH` (`brew install ffmpeg`) to run the transcoder on the host and for its tests. The container
  image ships its own.

## Run it

```bash
make toolchains        # once per machine: register JDK 21 for Maven
make keys              # once: generate the dev JWT key pair in secrets/ (git-ignored)
make up                # creates .env from .env.example if missing, starts infra (incl. Elasticsearch), waits until healthy
make run-api           # http://localhost:8080  (Swagger UI: /swagger-ui.html, health: /actuator/health)
make run-transcoder    # http://localhost:8081/actuator/health (needs ffmpeg: brew install ffmpeg)
make app               # …or run api + transcoder as containers (Compose profile "app")
make seed              # load the demo catalog through the real upload flow (needs api + transcoder running)
./scripts/verify-player.sh   # optional: headless-Chrome check that the hls.js page plays and seeks
make down              # stop infra (data kept in volumes; `docker compose down -v` wipes it)
```

### Web client

Prerequisites: the backend running with seeded data (above), and Node 22.12+ (Node 24 recommended).

```bash
make web               # = cd cadence-web && npm install && npm run dev  →  http://localhost:5173
```

Log in with the demo listener (`CADENCE_DEMO_EMAIL` / `CADENCE_DEMO_PASSWORD` from `.env`), or as the admin
(`CADENCE_ADMIN_*`) to see the **Catalog admin** page. The dev server proxies `/api` to `http://localhost:8080`
(override with `CADENCE_API_URL`). `make app` also starts the client as a container at http://localhost:3000.
`make web-verify` runs the browser check: headless Chrome logs in, finds "The Beatlz" by typing "beatls", starts
playback and navigates through five pages, asserting the same audio element keeps playing without a reload.

| Service | URL / port | Notes |
|---|---|---|
| Web client | http://localhost:5173 (`make web`) or :3000 (`make app`) | React app |
| cadence-api | http://localhost:8080 | REST API under `/api/v1` |
| cadence-transcoder | http://localhost:8081 | health only |
| Dev player | http://localhost:8080/dev/player.html | dev profile only (on by default in `make run-api` / `make app`) |
| PostgreSQL 16 | localhost:5432 | db/user from `.env` |
| Redis 7 | localhost:6379 | password from `.env` |
| Kafka (KRaft) | localhost:9092 (host), `kafka:29092` (on `cadence-net`) | |
| MinIO | http://localhost:9000 (S3), http://localhost:9001 (console) | private buckets `cadence-raw`, `cadence-hls`; apps use the least-privilege `cadence-app` user |
| Elasticsearch 8 | http://localhost:9200 | user `elastic`, password `ELASTIC_PASSWORD` from `.env` |
| kafka-ui | http://localhost:8090 | |

All config comes from environment variables, documented in [`.env.example`](.env.example). Both Compose and
the Spring apps read `.env`, which is git-ignored.

## Test

```bash
make test              # = ./mvnw verify : unit tests (*Test), Testcontainers integration tests (*IT),
                       #   the web client's typecheck, build and Vitest suite, and the acceptance suites
```

Integration tests start their own Postgres, Kafka, Redis, MinIO and Elasticsearch containers (transcoder tests run the real
`ffmpeg`). `cadence-e2e` boots the packaged api and transcoder jars as real processes and checks every Phase 1
acceptance criterion end to end. None of this needs `make up`.

### Try the auth API

```bash
curl -s -c jar -XPOST localhost:8080/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"me@example.com","password":"a-long-password","displayName":"Me"}'
# → {"accessToken":"…","expiresIn":900,"tokenType":"Bearer"}
#   plus Set-Cookie: cadence_refresh=…; Path=/api/v1/auth; HttpOnly; SameSite=Strict (never in the body)
curl -s localhost:8080/api/v1/me -H "Authorization: Bearer <accessToken>"
# rotate: the cookie is the credential, and the X-Cadence-CSRF header is required (DECISIONS.md D86)
curl -s -b jar -c jar -XPOST localhost:8080/api/v1/auth/refresh -H 'X-Cadence-CSRF: 1'
```

## Layout

```
cadence-events/        shared EventEnvelope, Topics, UuidV7, Jackson config, JSON schema
cadence-api/           com.cadence.{common, identity, catalog, library, streaming, search, activity}
cadence-transcoder/    Kafka consumer → FFmpeg → MinIO
cadence-web/           React + TypeScript + Vite + hls.js web client (Maven module via frontend-maven-plugin)
cadence-e2e/           acceptance tests against the packaged jars (Phase 1 and 2 criteria)
scripts/               toolchains, dev keys, seed.py, verify-player.sh, verify-web.sh
docker-compose.yml     postgres, redis, kafka, minio (+ bucket init), elasticsearch, kafka-ui on network cadence-net
```

Bounded contexts talk only through public interfaces and outbox events. `ModularityTest` (Spring Modulith)
fails the build on boundary violations.

## Working copy on an iCloud-synced Desktop

If the repository lives in an iCloud-synced folder (macOS "Desktop & Documents"), iCloud syncs Maven's `target/`
folders and `cadence-web/node_modules`, and it can restore deleted build files as `name 2` copies in the middle of a
build. That shows up as errors like "Found more than one migration with version 1" or `NoClassDefFoundError`. Keep
the repository outside synced folders (e.g. `~/dev/cadence`), or run `./mvnw clean verify` after iCloud is idle.
