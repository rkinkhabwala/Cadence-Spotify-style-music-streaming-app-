# Cadence

A Spotify-style music streaming app: a Java 21 / Spring Boot 3.5 modular monolith (`cadence-api`), a Kafka +
FFmpeg transcoding worker (`cadence-transcoder`), and shared event contracts (`cadence-events`). Everything runs
locally in Docker Compose. See [`spec.md`](spec.md) for the product spec, [`DECISIONS.md`](DECISIONS.md) for
assumptions, and [`PROGRESS.md`](PROGRESS.md) for what has been built.

## Status

**Phase 1, slice 1.1 (skeleton) is done.** What works now:

- Multi-module Maven build (`./mvnw`), JDK 21, virtual threads.
- `cadence-api` boots. It has Flyway (outbox + processed-event tables), `/actuator/health`, Swagger UI,
  RFC 7807 errors, cursor pagination helpers and UUIDv7 IDs. A transactional outbox polls into Kafka and
  creates every topic from spec 3.4 at startup.
- `cadence-transcoder` boots and consumes `catalog.track-uploaded`. It's a stub that only logs; FFmpeg comes in slice 1.4.
- `cadence-events` holds the shared `EventEnvelope` (spec 3.5), topic names, its JSON Schema and the Jackson settings.
- Not built yet: identity, catalog, uploads, playback, library and seed data (slices 1.2–1.6).

## Prerequisites

- **JDK 21.** The build refuses other versions. The `Makefile` finds JDK 21 automatically
  (`/usr/libexec/java_home -v 21`, or Homebrew `openjdk@21`). When calling `./mvnw` directly, run
  `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` first, or on Homebrew `export JAVA_HOME=/opt/homebrew/opt/openjdk@21`.
- **Docker** (Docker Desktop or compatible), for Compose and Testcontainers.

## Run it

```bash
make up                # creates .env from .env.example if missing, starts infra, waits until healthy
make run-api           # http://localhost:8080  (Swagger UI: /swagger-ui.html, health: /actuator/health)
make run-transcoder    # http://localhost:8081/actuator/health
make down              # stop infra (data kept in volumes; `docker compose down -v` wipes it)
```

| Service | URL / port | Notes |
|---|---|---|
| cadence-api | http://localhost:8080 | REST API under `/api/v1` |
| cadence-transcoder | http://localhost:8081 | health only |
| PostgreSQL 16 | localhost:5432 | db/user from `.env` |
| Redis 7 | localhost:6379 | password from `.env` |
| Kafka (KRaft) | localhost:9092 (host), `kafka:29092` (on `cadence-net`) | |
| MinIO | http://localhost:9000 (S3), http://localhost:9001 (console) | private buckets `cadence-raw`, `cadence-hls` |
| kafka-ui | http://localhost:8090 | |

All config comes from environment variables, documented in [`.env.example`](.env.example). Both Compose and
the Spring apps read `.env`, which is git-ignored.

## Test

```bash
make test              # = ./mvnw verify : unit tests (*Test) + Testcontainers integration tests (*IT)
```

Integration tests start their own Postgres and Kafka containers, so they don't need `make up`.

## Layout

```
cadence-events/        shared EventEnvelope, Topics, UuidV7, Jackson config, JSON schema
cadence-api/           com.cadence.{common, identity, catalog, library, streaming, search, activity}
cadence-transcoder/    Kafka consumer → FFmpeg → MinIO (stub for now)
docker-compose.yml     postgres, redis, kafka, minio (+ bucket init), kafka-ui on network cadence-net
```

Bounded contexts talk only through public interfaces and outbox events. `ModularityTest` (Spring Modulith)
fails the build on boundary violations.
