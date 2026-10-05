# CLAUDE.md — Cadence engineering rules

`spec.md` is the source of truth. **Section 12 "Decisions" overrides anything earlier in the spec.**
When the spec is ambiguous or silent, pick the simplest option consistent with it, record it in
`DECISIONS.md`, and keep going. Ask the user only about choices that are hard to reverse.

## Stack
- Java 21 (Homebrew `openjdk@21`; the Makefile sets `JAVA_HOME`), Spring Boot 3.x (3.5 line), Maven multi-module via `./mvnw`.
- Virtual threads enabled (`spring.threads.virtual.enabled=true`) in every app.
- Modules: `cadence-events`, `cadence-api`, `cadence-transcoder`, `cadence-web` (Phase 2).

## Architecture
- Package layout per spec 3.1: `com.cadence.{common,identity,catalog,library,streaming,search,activity}`.
- Inside each bounded context: `api/` (controllers + DTOs), `application/` (services/use cases),
  `domain/` (entities, value objects, events), `infrastructure/` (repositories, clients).
- Bounded contexts never touch each other's repositories or tables. They talk through public
  interfaces (types in the context's base package) or events. `common` is an OPEN shared-kernel module.
- Boundaries are enforced by the Spring Modulith verification test (`ModularityTest`); it must stay green.
- Table ownership: every table belongs to exactly one context (listed in `DECISIONS.md`). Cross-context
  references are plain UUID columns without foreign keys.

## Coding conventions (spec 11)
- Records for DTOs and value objects. Entities stay in `domain/` and never leave the application layer;
  controllers return DTOs mapped with MapStruct.
- Constructor injection only. No field `@Autowired`.
- IDs are UUIDv7 (`com.cadence.events.UuidV7`, shared with the transcoder). Timestamps are `Instant` in UTC.
- Errors are RFC 7807 `ProblemDetail`, thrown as `CadenceException` subclasses and rendered by `GlobalExceptionHandler`.
- Collections use cursor pagination: `?limit=&cursor=` → `{ items, nextCursor }` (`CursorPage`).
- Every write endpoint is idempotent or documents why not.
- Base path `/api/v1` (`ApiPaths.V1`).

## Persistence and messaging
- Every schema change is a Flyway migration in `cadence-api/src/main/resources/db/migration`.
  **Never edit a migration that has been committed**; add a new one.
- Cross-context events go through the transactional outbox (`OutboxWriter`, same DB transaction as
  the state change). The `OutboxPoller` publishes to Kafka.
- Every event uses the shared `EventEnvelope` from `cadence-events`; topic names live in `Topics`.
- Every consumer is idempotent: it records processed event IDs (`ProcessedEvents`) in the same transaction as its effect.

## Testing
- Unit tests (`*Test`, surefire) for domain logic, with no Spring context.
- Integration tests (`*IT`, failsafe) use Testcontainers (Postgres, Kafka, Redis, MinIO, Elasticsearch as needed).
  Never mock databases.
- Every endpoint gets at least one happy-path and one failure-path integration test.
- `./mvnw verify` must be green with no skipped tests before moving on.

## Dependencies and config
- Don't add libraries or features outside the spec without recording why in `DECISIONS.md`.
- Never hardcode secrets. All config goes through `application.yml` with env vars, each documented in `.env.example`.
  `.env` is git-ignored. Private keys are never committed.

## Workflow per slice
1. Short plan (files, migrations, endpoints, tests).
2. Implement.
3. `./mvnw verify` (or `make test`) green.
4. Update `README.md` and append to `PROGRESS.md` (slice, what was built, test count, assumptions).
5. Commit with a conventional-commit message, e.g. `feat(identity): jwt auth with refresh rotation`.
- At the end of each phase: print the spec-9 acceptance checklist (PASS/FAIL + proving test) and wait for "continue".
