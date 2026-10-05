# PROGRESS.md

Append-only log of completed slices.

## Slice 1.1 — Skeleton (2026-10-05)

**Built**
- Parent POM (Boot 3.5.16 BOM, Modulith, Testcontainers, AWS SDK v2, Resilience4j BOMs, plus MapStruct, springdoc and Bucket4j versions), Maven wrapper 3.9.16, JDK 21 enforcer.
- `cadence-events`:
  - `EventEnvelope` (spec 3.5) plus a JSON Schema
  - `Topics`, `EventTypes`, `ItemTypes`, `UuidV7`
  - `CadenceJackson` plus an auto-configuration shared by both apps
- `cadence-api`:
  - Boots with Flyway `V1` (`outbox_event`, `processed_event`), Actuator health, springdoc Swagger UI
  - `GlobalExceptionHandler` (RFC 7807 with `code`/`type`, validation `errors[]`)
  - Cursor pagination (`Cursor`, `CursorRequest`, `CursorPage`)
  - `OutboxWriter` (MANDATORY tx) and `OutboxPoller` (SKIP LOCKED, ordered, at-least-once, 7-day purge)
  - `ProcessedEvents` idempotency guard, Kafka topic declarations, UTC `Clock`
  - `common` declared as an OPEN Modulith module
- `cadence-transcoder`: boots with a `catalog.track-uploaded` listener stub (`TranscodeJobHandler`).
- `docker-compose.yml`: postgres 16, redis 7 (password), kafka 3.9.1 KRaft, minio + `minio-init` (private `cadence-raw` and `cadence-hls`), kafka-ui. All on `cadence-net`, with healthchecks. Also `Makefile` (up/down/logs/seed/test/run-*), `.env.example`, `.gitignore`.
- Verified manually: `make up` → all services healthy; both apps boot from `.env` against Compose; all 7 topics are created; a 404 renders as a problem detail.

**Tests: 33 passing, 0 skipped.**
- cadence-events: 10 unit tests (envelope, schema, UUIDv7).
- cadence-api: 13 unit/slice tests (Cursor, GlobalExceptionHandler, Modularity) and 9 integration tests (context and health, Flyway, OpenAPI/Swagger, problem 404, outbox commit/rollback/MANDATORY, processed events).
- cadence-transcoder: 1 integration test (real Kafka → listener).

**Assumptions:** DECISIONS.md D1–D18. Notable ones:
- `minio/minio` images are gone from Docker Hub, so the build uses `pgsty/minio` (D3).
- Builds are pinned to JDK 21 because the machine default is JDK 26 (D2).

## Slice 1.2 — Identity

**Plan**
- Migration `V2__identity_users_and_refresh_tokens.sql`: `users`, `user_roles`, `refresh_tokens` (hashed, with family id).
- `common.security`: RS256 key loading from PEM files (`scripts/generate-dev-keys.sh`), `JwtEncoder`/`JwtDecoder`, stateless filter chain, RFC 7807 401/403 handlers, `CurrentUser` argument resolver.
- `common.ratelimit`: Bucket4j and Redis (Lettuce) `RateLimiter`, plus a 429 problem with `Retry-After`.
- `identity`: register/login/refresh (rotation and reuse detection that revokes the family)/logout, GET/PATCH `/me`, `/.well-known/jwks.json`, admin bootstrap from `CADENCE_ADMIN_EMAIL`/`CADENCE_ADMIN_PASSWORD`, public `UserAccounts` API (plan lookup) for other contexts.
- Tests: refresh-token and password-policy unit tests, plus `AuthIT` (full lifecycle, expired access token with valid refresh, reuse, logout, 401/403 cases, validation, JWKS verification, rate limit) and an admin-bootstrap IT.

**Built**
- `common.security`:
  - PEM-loaded RS256 key with a thumbprint `kid`, `JwtEncoder`, and an access `JwtDecoder` (issuer, expiry and `token_use` checks), plus `JwtDecoders` for other token uses
  - Stateless filter chain with every route rule in one place; RFC 7807 401/403 with `WWW-Authenticate`
  - `CurrentUser` argument resolver; `@EnableMethodSecurity`
- `common.ratelimit`: Bucket4j and Redis `RateLimiter` (fails open), named limits in config, 429 with `Retry-After`.
- `common.error`: `TooManyRequestsException`, `UnauthorizedException`; handlers for `AccessDeniedException`, `AuthenticationException` and optimistic-lock conflicts.
- `identity` context:
  - Domain: `User`, `RefreshToken` (rotate/reuse/revoke), `PasswordPolicy`
  - Spring Data repositories, `AuthService`, `ProfileService` (MapStruct → `UserProfile`), `TokenService`, `AdminBootstrap`
  - Public `UserAccounts` API; `AuthController`, `MeController`, `JwksController`
- Migration `V2`. `make keys` / `scripts/generate-dev-keys.sh`. Redis config and `server.forward-headers-strategy=native`.
- Verified manually against Compose: the admin is bootstrapped from `.env`, and the 6th login attempt from one IP within a minute gets 429.

**Tests: 57 passing, 0 skipped** (+24 since 1.1).
- New unit tests: `RefreshTokenTest` (3), `PasswordPolicyTest` (2), `GlobalExceptionHandlerTest` (+2).
- New integration tests:
  - `AuthIT` (14): register, validation, duplicate email, login, rate limit, full lifecycle, expired access token with valid refresh, reuse revokes the family, unknown refresh token, PATCH /me, invalid/foreign/wrong-use tokens, 401/403 on admin routes, JWKS verification
  - `AdminBootstrapIT` (2)
  - `CadenceApiApplicationIT` (+1)

**Assumptions:** D19–D27. Notably:
- Refresh-token families, and the rule that concurrent reuse revokes the session (D20).
- `token_use` claim (D21).
- The rate limiter fails open (D23).
- No HTTP endpoint changes a user's plan (D26).

## Slice 1.3 — Catalog and uploads

**Plan**
- Migration `V3__catalog.sql`: `artists`, `albums`, `genres`, `album_genres`, `tracks` (status, source key, transcode job id, loudness, failure reason), `track_artists`.
- `common.storage`: S3 (AWS SDK v2, path-style) `ObjectStorage` for presigned PUT/GET, HEAD, range reads and prefix deletes. A separate public endpoint is used for presigning, and buckets can be auto-created in dev/test.
- `catalog` domain:
  - `Artist`, `Album`, `Genre`, `Track` (DRAFT → PROCESSING → READY/FAILED state machine with a job id), `TrackArtist` credits
  - `AudioFormat` (extension, MIME type, magic-byte sniffing)
- Public reads: `GET /artists/{id}` (+ top 10 READY tracks), `/artists/{id}/albums` (cursor), `/albums/{id}`, `/tracks/{id}`, `/genres`.
- Admin: POST/PATCH/DELETE for artists, albums and tracks; `upload-url` (presigned PUT to `raw/{trackId}/source.{ext}`, size-signed, ≤ 200 MB); `upload-complete` (HEAD + magic bytes → PROCESSING + `catalog.track-uploaded` via the outbox); `retranscode`; `GET /admin/tracks?status=`.
- `catalog.entity-changed` is written to the outbox on every create/update/delete, with a snapshot of the entity (for search in Phase 2).
- Public `CatalogQueries` API (`TrackSummary` hydration) for library and streaming.
- Tests: Track state-machine and AudioFormat unit tests. ITs with Testcontainers Postgres, Kafka and MinIO: admin CRUD and validation, 403/404/409, public reads, a real presigned upload, upload-complete events on Kafka, idempotency, retranscode.

**Built**
- `common.storage`: `S3Properties`, `S3Config` (path-style client plus a presigner on the public endpoint), `ObjectStorage` (presigned PUT signed for type and length, presigned GET, HEAD, range read, put, delete, prefix delete, list; optional bucket auto-create).
- `cadence-events`: `TrackUploadedPayload`, `TrackTranscodedPayload`, `TrackTranscodeFailedPayload`, `EntityChangedPayload`, unlike/unfollow event types, and an `EventEnvelope.create` overload with a caller-chosen id.
- `catalog`:
  - Public API: `CatalogQueries`, `TrackSummary`, `CatalogRefs`, `TrackStatus`
  - Domain: `Artist`, `Album`, `Genre`, `Track` (state machine and job id), `TrackArtist`, `AudioFormat`
  - Repositories with keyset queries; `CatalogReadService`, `ArtistAdminService`, `AlbumAdminService`, `TrackAdminService`, `TrackUploadService`, `CatalogEvents`, `TrackSummaries` (no N+1), MapStruct `CatalogMapper`
  - `CatalogController` (public) and `AdminCatalogController`
- Migration `V3`. Public catalog GET rules in `SecurityConfig`. `minio-init` creates the least-privilege `cadence-app` user.
- Rate limiter switched to interval refill (D23). Tests pin the JDK HTTP client (D37).
- Verified manually against Compose with the least-privilege credentials: create, presigned PUT, upload-complete, event on Kafka, delete with storage cleanup.

**Tests: 86 passing, 0 skipped** (+29).
- New unit tests: `TrackTest` (7), `AudioFormatTest` (3).
- New integration tests:
  - `AdminCatalogIT` (8): CRUD, outbox events, defaults/genres, validation, delete conflicts, PATCH, 403/401, dashboard filter and pagination
  - `CatalogPublicIT` (5): top 10 by play count with READY only, album pagination, tracklist order, track 404 unless READY, genres
  - `UploadIT` (6): real presigned upload, event on Kafka, idempotent complete, URL validation, storage rejecting a wrong size, missing upload, non-audio rejected and deleted, retranscode rules

**Assumptions:** D28–D37.
