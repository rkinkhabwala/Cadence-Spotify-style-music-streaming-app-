# Cadence

A Spotify-style music streaming app: a Java 21 / Spring Boot 3.5 modular monolith (`cadence-api`), a Kafka +
FFmpeg transcoding worker (`cadence-transcoder`), and shared event contracts (`cadence-events`). Everything runs
locally in Docker Compose. See [`spec.md`](spec.md) for the product spec, [`DECISIONS.md`](DECISIONS.md) for
assumptions, and [`PROGRESS.md`](PROGRESS.md) for what has been built.

## Status

**Phase 1: slices 1.1–1.5 (skeleton, identity, catalog/uploads, transcoding/streaming, library) are done.** What works now:

- Multi-module Maven build (`./mvnw`), JDK 21, virtual threads.
- `cadence-api` boots. It has Flyway (outbox + processed-event tables), `/actuator/health`, Swagger UI,
  RFC 7807 errors, cursor pagination helpers and UUIDv7 IDs. A transactional outbox polls into Kafka and
  creates every topic from spec 3.4 at startup.
- `cadence-transcoder` boots and consumes `catalog.track-uploaded`. It's a stub that only logs; FFmpeg comes in slice 1.4.
- `cadence-events` holds the shared `EventEnvelope` (spec 3.5), topic names, its JSON Schema and the Jackson settings.
- **Identity:** register, login (rate-limited to 5 per minute per IP), refresh-token rotation with reuse
  detection, logout, `GET/PATCH /api/v1/me`, RS256 JWTs (15 min) with public keys at `/.well-known/jwks.json`.
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
- Not built yet: seed data (slice 1.6).

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
make up                # creates .env from .env.example if missing, starts infra, waits until healthy
make run-api           # http://localhost:8080  (Swagger UI: /swagger-ui.html, health: /actuator/health)
make run-transcoder    # http://localhost:8081/actuator/health (needs ffmpeg: brew install ffmpeg)
make app               # …or run api + transcoder as containers (Compose profile "app")
make down              # stop infra (data kept in volumes; `docker compose down -v` wipes it)
```

| Service | URL / port | Notes |
|---|---|---|
| cadence-api | http://localhost:8080 | REST API under `/api/v1` |
| cadence-transcoder | http://localhost:8081 | health only |
| Dev player | http://localhost:8080/dev/player.html | dev profile only (on by default in `make run-api` / `make app`) |
| PostgreSQL 16 | localhost:5432 | db/user from `.env` |
| Redis 7 | localhost:6379 | password from `.env` |
| Kafka (KRaft) | localhost:9092 (host), `kafka:29092` (on `cadence-net`) | |
| MinIO | http://localhost:9000 (S3), http://localhost:9001 (console) | private buckets `cadence-raw`, `cadence-hls`; apps use the least-privilege `cadence-app` user |
| kafka-ui | http://localhost:8090 | |

All config comes from environment variables, documented in [`.env.example`](.env.example). Both Compose and
the Spring apps read `.env`, which is git-ignored.

## Test

```bash
make test              # = ./mvnw verify : unit tests (*Test) + Testcontainers integration tests (*IT)
```

Integration tests start their own Postgres, Kafka, Redis and MinIO containers (transcoder tests run the real
`ffmpeg`), so they don't need `make up`.

### Try the auth API

```bash
curl -s -XPOST localhost:8080/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"me@example.com","password":"a-long-password","displayName":"Me"}'
# → {"accessToken":"…","refreshToken":"…","expiresIn":900,"tokenType":"Bearer"}
curl -s localhost:8080/api/v1/me -H "Authorization: Bearer <accessToken>"
```

## Layout

```
cadence-events/        shared EventEnvelope, Topics, UuidV7, Jackson config, JSON schema
cadence-api/           com.cadence.{common, identity, catalog, library, streaming, search, activity}
cadence-transcoder/    Kafka consumer → FFmpeg → MinIO
docker-compose.yml     postgres, redis, kafka, minio (+ bucket init), kafka-ui on network cadence-net
```

Bounded contexts talk only through public interfaces and outbox events. `ModularityTest` (Spring Modulith)
fails the build on boundary violations.
