# DECISIONS.md

Assumptions and choices made where `spec.md` is ambiguous or silent, plus every library or feature
added beyond the spec. Spec section 12 decisions are authoritative and are not repeated here.

| # | Slice | Topic | Decision | Why |
|---|---|---|---|---|
| D1 | 1.1 | Spring Boot version | **3.5.16**, the newest 3.x release. Boot 4.x exists but the spec pins 3.x. Companions: Spring Modulith 1.4.13, springdoc 2.9.1 (built against Boot 3.5.16), Testcontainers 1.21.4 (Boot-managed). | Spec section 2. |
| D2 | 1.1 | JDK | The build enforces JDK 21 (`maven-enforcer` `[21,22)`) with `--release 21`. The machine's default JDK is 26/27, so the `Makefile` points `JAVA_HOME` at JDK 21 (`/usr/libexec/java_home -v 21`, falling back to Homebrew `openjdk@21`). | Reproducibility. Mockito/ByteBuddy and Spring Boot 3.5 don't officially support JDK 26+. |
| D3 | 1.1 | MinIO image | `minio/minio` and `minio/mc` are no longer published on Docker Hub or quay.io (pulls fail). Compose and Testcontainers use the community rebuild **`pgsty/minio:RELEASE.2026-08-04T00-00-00Z`**: the same MinIO server and S3 API, and it ships `mc`. | Spec says MinIO. This keeps the same software from a reachable registry. Swapping it back later is a one-line change. |
| D4 | 1.1 | kafka-ui image | `ghcr.io/kafbat/kafka-ui:v1.3.0`, the maintained fork of `provectuslabs/kafka-ui`, which is unmaintained. | Same product, maintained. |
| D5 | 1.1 | Bucket-init healthcheck | The one-shot `minio-init` container has no healthcheck because it exits after creating buckets. Dependents use `condition: service_completed_successfully`, which is the equivalent readiness signal. Every long-running service has a healthcheck. | A healthcheck on an exited container is meaningless. |
| D6 | 1.1 | Table ownership | One Postgres database, `public` schema. Each table is owned by one context (see the table below). Cross-context references are UUID columns **without** foreign keys. | Simplest option. Spring Modulith enforces code-level access, and no FKs keeps contexts independently evolvable. |
| D7 | 1.1 | Base path | Controllers use `@RequestMapping(ApiPaths.V1 + "/...")` rather than a servlet context path, so `/actuator/**` and `/.well-known/jwks.json` stay at the root. | Spec 5 and 6. |
| D8 | 1.1 | UUIDv7 | Own small generator, `com.cadence.events.UuidV7` (RFC 9562, monotonic within a millisecond), instead of a library. It lives in `cadence-events` because the transcoder also mints event IDs. Hibernate 6.6 has no v7 style. Entities assign IDs in their constructors. | Avoids an extra dependency. |
| D9 | 1.1 | Outbox | Table `outbox_event` written via `OutboxWriter` (`Propagation.MANDATORY`, so it can only join an existing transaction). `OutboxPoller` runs every 500 ms (`CADENCE_OUTBOX_POLL_INTERVAL`), claims up to 100 rows with `FOR UPDATE SKIP LOCKED` in creation order, sends each synchronously to Kafka, marks `published_at`, and stops the batch at the first failure to keep per-key order. Published rows are purged after 7 days. Kafka record value is the envelope JSON (String serde). Delivery is at-least-once, which is why consumers are idempotent. | Spec 3.4 offers "outbox table + poller or Debezium". The poller is simpler. |
| D10 | 1.1 | Processed events | Table `processed_event(consumer, event_id)` with `ProcessedEvents.markProcessed(consumer, eventId)` → `INSERT … ON CONFLICT DO NOTHING`, called in the consumer's transaction. | Idempotent consumers (spec 3.4). |
| D11 | 1.1 | Problem details | `type` = `https://cadence.dev/problems/{code}` (an identifier, not a resolvable URL). Every problem also carries a machine-readable `code` property. Validation errors add an `errors: [{field, message}]` array. Unexpected 500s never leak exception messages. | RFC 7807. |
| D12 | 1.1 | Cursor format | Opaque base64url of a small JSON array of sort-key values (e.g. `[createdAt, id]`). Page size `limit` defaults to 20 and is clamped to 1..100. A malformed cursor returns a 400 problem. | Spec 5 says only `?limit&cursor`. |
| D13 | 1.1 | Event envelope | `EventEnvelope.payload` is a Jackson `JsonNode`, so one record serves every event type and the recommender. Typed payload records convert with `payloadAs(...)`. A JSON Schema for the envelope ships in `cadence-events` (`schemas/event-envelope.schema.json`). `cadence-events` auto-configures the shared Jackson settings (ISO-8601 instants, ignore unknown properties) for both apps. | Spec 3.1 and 3.5. |
| D14 | 1.1 | Config loading | Apps read `.env` via `spring.config.import: optional:file:.env[.properties]` (repo root and module directory), so `./mvnw spring-boot:run` works with the same `.env` as Compose. Secrets have no defaults in `application.yml`. | Single source of local config, no committed secrets. |
| D15 | 1.1 | Test split | `*Test` = unit tests (surefire). `*IT` = Testcontainers integration tests (failsafe). Both run in `./mvnw verify`. | Standard Maven split. |
| D16 | 1.1 | Awaitility (test only) | Added `org.awaitility:awaitility` (version managed by the Boot BOM) for asynchronous assertions on Kafka/outbox delivery. | Avoids hand-written sleep/poll loops in event-driven tests. |
| D17 | 1.1 | Infra ports and auth | Compose binds every port to `127.0.0.1` only. Redis requires a password (`REDIS_PASSWORD`). `make up` waits for the long-running services (`--wait`), then runs `minio-init` in the foreground, because `--wait` reports a finished one-shot container as a failure. | Local-only (spec 12 #4), no secrets in code. |
| D18 | 1.1 | Event payload equality | `EventEnvelope.create` builds the payload by serializing and re-parsing, not with `valueToTree`, so a freshly created envelope `equals()` its Kafka round-tripped copy (numeric node types match). | Makes dedupe and test comparisons reliable. |

## Table ownership

| Table | Owning context | Slice |
|---|---|---|
| `outbox_event`, `processed_event` | common (shared kernel) | 1.1 |
