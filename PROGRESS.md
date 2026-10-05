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

## Slice 1.4 — Transcoder and streaming

**Plan**
- `cadence-events`: `HlsLayout`, the shared object-key layout written by the transcoder and read by the API.
- `cadence-transcoder`:
  - Consumes `catalog.track-uploaded`, downloads the source, probes duration with `ffprobe`, and runs one FFmpeg pass that writes AAC HLS at 96/160/320 kbps (10 s segments, `master.m3u8`) plus a single-file 160 kbps fallback. A second pass measures EBU R128 loudness.
  - Uploads to `hls/{trackId}/` with a `result.json` marker (makes duplicates idempotent) and publishes `streaming.track-transcoded` or `-failed`.
  - `ProcessBuilder` runner with a timeout and captured stderr; the temp dir is always deleted. After retries, infrastructure errors become a `-failed` event.
- `catalog`: consumer of transcode results (processed-event dedupe plus job-id check) → READY/FAILED with `durationMs` and loudness; emits `entity-changed`.
- `streaming` context:
  - `POST /playback/{trackId}`: READY only; returns `{manifestUrl, expiresAt, durationMs}`.
  - Manifests are served by the API (`/playback/{id}/master.m3u8`, `/playback/{id}/{variant}/index.m3u8`), authorized by short-lived playback JWTs in the URL (`token_use=playback`). The master is filtered by plan (FREE: 96/160 only) and its variant URIs rewritten; variant playlists have segment URIs rewritten to presigned MinIO GETs.
  - `GET /tracks/{id}/stream`: HTTP Range fallback on the 160 kbps file (206/416).
  - `/dev/player.html` (dev profile only) using hls.js.
- Dockerfiles for API and transcoder (the transcoder image includes FFmpeg), plus the Compose profile `app`.
- Tests:
  - Unit: playlist rewriting, Range parsing, FFmpeg output parsing
  - Transcoder IT: a real FFmpeg 10 s sine tone through Kafka and MinIO; failure; duplicate delivery
  - API ITs: playback and manifests (free vs premium, tokens), Range requests, transcode-result consumer

**Built**
- `cadence-events`: `HlsLayout`.
- `cadence-transcoder`:
  - `TranscodeJobHandler` (idempotent via `result.json`, per-job temp dir always deleted)
  - `Ffmpeg` (ffprobe audio/duration, one-pass HLS ×3 plus fallback, ebur128 loudness) and `ProcessRunner` (timeout, stderr captured to files)
  - `HlsStorage` (S3), `ResultPublisher`, and an error handler that publishes `-failed` after 3 retries
- `catalog`: `TranscodeResultService` and listener (processed-event dedupe, job-id check, `entity-changed` on READY/FAILED).
- `streaming` context:
  - Domain: `HlsPlaylists` (master filter, media rewrite) and `ByteRange`
  - Application: `PlaybackTokens`, `PlaybackService`, `StreamService`
  - API: `PlaybackController` (POST /playback, master/variant playlists, Range stream) and `DevPlayerConfig` plus `dev/player.html`
- `common`: `CadenceException.headers()` (Retry-After, Content-Range), API Kafka error handler, permitAll for token-authorized playlists and `/dev/**`.
- `cadence-api/Dockerfile`, `cadence-transcoder/Dockerfile` (with ffmpeg), `.dockerignore`, Compose profile `app` (`make app`, `make app-down`).
- FFmpeg 9.0.2 installed with Homebrew (needed by transcoder tests and host runs).
- **Verified end to end with the containers:** an admin uploaded a real 3-minute MP3 and it was READY after 14.2 s. MinIO holds `master.m3u8`, 3 renditions × 19 segments, `fallback_160k.m4a` and `result.json`. A free user's manifest lists 2 variants, and `ffmpeg -ss 150 -i <manifestUrl>` seeks and decodes through the presigned segments.

**Tests: 122 passing, 0 skipped** (+36).
- Transcoder:
  - `FfmpegTest` (4)
  - `TranscoderIT` (4): real 10 s sine tone → 3 renditions, duration and loudness; duplicate delivery doesn't re-transcode; invalid audio → failed, temp dir clean; missing source → failed
- API unit tests: `HlsPlaylistsTest` (4), `ByteRangeTest` (11), `DevPlayerPageTest` (1).
- API integration tests:
  - `PlaybackIT` (4): free vs premium renditions, presigned segments downloadable, TTL, 403 for 320k, token tamper/track/scope/use checks
  - `StreamRangeIT` (5): `0-1023`, open-ended, suffix, full, 416 cases, auth and READY
  - `TranscodeResultIT` (3): exactly once, stale job, failed then retranscode
  - `CadenceApiApplicationIT` (+1): dev page hidden without the dev profile

**Assumptions:** D38–D45. Notably, segment URLs live 5 min + track duration so long tracks can be seeked (D38).

## Slice 1.5 — Library

**Plan**
- Migration `V4__library.sql`: `playlists` (version, track_count), `playlist_tracks` (fractional `position` in `COLLATE "C"`, unique per playlist), `liked_tracks`, `followed_artists`, `saved_albums`.
- Domain:
  - `FractionalIndex`, a port of the fractional-indexing algorithm (base-62 keys; appends stay short)
  - `Playlist` (ownership, visibility, optimistic `@Version`)
  - `PlaylistTrack`, `LikedTrack`, `FollowedArtist`, `SavedAlbum`
- Playlists:
  - `GET /me/playlists`; `POST/GET/PATCH/DELETE /playlists/{id}`. PATCH requires `If-Match` (412 on mismatch, 428 when missing).
  - Add (with `position`), remove, reorder (`afterTrackId`). The playlist row is version-checked before track rows change, so concurrent edits never lose writes.
  - At most 10,000 tracks.
- Likes and follows: idempotent `PUT`/`DELETE /me/likes/tracks/{id}` and `/me/following/artists/{id}`, plus `GET /me/likes/tracks`. Events `library.track-liked` / `library.artist-followed` (and their un- counterparts) go through the outbox in the shared envelope (`itemType` song/artist).
- Saved albums (spec 4 entity): `PUT/DELETE/GET /me/albums`.
- Tests: FractionalIndex unit tests (including randomized). ITs for create/add/reorder/persisted order, positional insert, pagination, idempotent like/unlike and follow/unfollow with events, ownership 403/404, version conflicts (412/428), concurrent edits, the 10,000 limit.

**Built**
- `library` context:
  - Domain: `FractionalIndex`, `Playlist` (visibility, ownership checks, `@Version`, 10,000-track limit), `PlaylistTrack`, `LikedTrack`, `FollowedArtist`, `SavedAlbum`, `Visibility`
  - Repositories with keyset pages and race-free `ON CONFLICT` writes
  - `PlaylistService` (version-checked playlist row flushed before track rows), `LibraryService`, `LibraryEvents` (outbox), MapStruct `LibraryMapper`
  - `PlaylistController` (ETag / If-Match) and `LibraryController`
- `common.web.ETags` (strong version ETags, If-Match parsing, 428 helper). `CatalogQueries.findAlbums`.
- Migration `V4`.

**Tests: 140 passing, 0 skipped** (+18).
- Unit: `FractionalIndexTest` (5): known sequences, 10,000 appends ≤ 4 characters, bulk inserts, 5,000 random inserts and moves, invalid keys.
- Integration:
  - `PlaylistIT` (8): create, add 3, reorder, persisted order (other positions untouched); positional insert and pagination; idempotent add/remove and READY-only; private 404 / public read / owner-only 403 / anonymous 401; 428/412/ETag; 8 concurrent adds with no lost writes; the 10,000 limit; `/me/playlists`
  - `LibraryIT` (5): idempotent like/unlike with exactly one event per change, likes order and pagination, READY-only likes, idempotent follow/unfollow with artist events, saved albums

**Assumptions:** D46–D53.

## Slice 1.6 — Seed and Phase 1 acceptance

**Plan**
- `make seed`: `scripts/seed.py` (generated or user-supplied audio → real upload flow → wait for READY; demo listener).
- `cadence-e2e`: `Phase1AcceptanceIT` against the packaged jars and Testcontainers.
- Close the endpoint failure-path gaps.
- Headless-Chrome check of the hls.js page.
- Print the Phase 1 checklist.

**Built**
- `scripts/seed.py` and `make seed`; `seed-audio/` (git-ignored, `.gitkeep`); `CADENCE_DEMO_*` in `.env.example`.
- `cadence-e2e` module: `CadenceStack` (containers plus api and transcoder child processes), `Http`, `Media` (FFmpeg/ffprobe HLS client), `Phase1AcceptanceIT` (one test per criterion).
- `EndpointFailurePathsIT` closes the remaining failure-path gaps (admin GET track, reorder errors, auth on library reads, cursors, logout validation, JWKS method).
- `dev/player.html` autotest mode; `scripts/verify-player.sh`. Dockerfiles copy the new module's POM.
- Verified manually against Compose:
  - `make seed` created 5 artists, 10 albums and 20 tracks (mp3/flac/wav/m4a), all READY, plus the demo listener.
  - `verify-player.sh`: headless Chrome played the 160k rendition with hls.js and seeked to 12.6 s (PASS).

**Tests: 151 passing, 0 skipped** (+11).
- `cadence-events` 10; `cadence-api` 51 unit + 76 integration; `cadence-transcoder` 4 + 4; `cadence-e2e` 6.
- In the acceptance run, a 30 s MP3 was READY 2.7 s after upload-complete.

**Assumptions:** D54–D57.

## Slice 2.1 — Search and catalog caching

**Plan**
- Infra: Elasticsearch 8.19.22 in Compose (single node, basic auth, 512 MB heap, `127.0.0.1:9200`) and in Testcontainers;
  `spring-boot` Elasticsearch client (`elasticsearch-java`). Config via `CADENCE_ELASTICSEARCH_*` env vars.
- `search` context (owns the ES indices `cadence-artists`, `cadence-albums`, `cadence-tracks`, `cadence-playlists`):
  - Index setup at startup (folding analyzer, `search_as_you_type` subfields). When an index is newly created, the
    catalog and public playlists are replayed through the outbox so existing data gets indexed.
  - `SearchIndexer`: consumes `catalog.entity-changed` (artists, albums, READY tracks only) and a new
    `library.playlist-changed` (PUBLIC playlists only). Full-document upserts make it idempotent; artist/album renames are
    propagated into denormalized track/album docs with `update_by_query`.
  - `GET /search?q=&types=&limit=&cursor=`: fuzzy + prefix, grouped by type; track hits hydrated from the catalog.
  - `GET /search/suggest?q=`: mixed top suggestions (artists, tracks, albums, playlists).
  - Rate limit 30/s per user on both (spec 6). `POST /admin/search/reindex` rebuilds the indices.
- `catalog`: public `CatalogReplay` (republishes `entity-changed` for every entity). Redis cache for artist and album pages
  (TTL 10 min, cleared after commit on every catalog change, fails open).
- `library`: `library.playlist-changed` events on create/update/delete; public `PlaylistReplay`.
- `identity`: `UserAccounts.displayNames` (playlist owner names in search results).
- Seed: fuzzy-search fixture artist renamed to "The Beatlz".
- Tests: unit tests for query/type parsing and document mapping. `SearchIT` covers "beatls" fuzzy suggestions, an edit
  reflected within 5 s, rename propagation, READY-only tracks, playlists, types/limit/cursor, validation, 401/403/429,
  suggest latency and reindex. `CatalogCacheIT` covers cache hits and eviction.

**Built**
- Compose `elasticsearch` 8.19.22 (basic auth, health-checked, `es-data` volume); the `api` container waits for it. New
  `.env` keys: `ELASTIC_PASSWORD`, `ELASTICSEARCH_PORT`, `CADENCE_ELASTICSEARCH_URIS`, `CADENCE_ELASTICSEARCH_USERNAME`,
  `CADENCE_SEARCH_INDEX_PREFIX`.
- `search` context:
  - Domain: `SearchType`, `SearchDocuments` (snapshot → document mapping, READY/PUBLIC rules), `SearchUnavailableException`
  - `SearchStore`: Elasticsearch index lifecycle, upserts, real-time gets, rename propagation via `update_by_query`,
    `_msearch` search and the cross-index suggest query
  - Application: `SearchIndexer`, `SearchIndexAdmin` (create + replay), `SearchService`, MapStruct `SearchMapper`
  - `SearchController` (`/search`, `/search/suggest`), `SearchAdminController` (`/admin/search/reindex`), plus an
    indexer listener with its own never-skip retry policy
- `catalog`: `CatalogReplay`; Redis page cache (`CatalogCacheConfig`, `@Cacheable` artist/album, after-commit clearing
  in `CatalogEvents`); `common.config.CacheConfig` (fail-open cache errors).
- `library`: `library.playlist-changed` events, `PlaylistReplay`. `identity`: `UserAccounts.displayNames`.
- `cadence-e2e`: the stack now includes Elasticsearch.
- Seed: fixture artist "The Beatlz", with in-place rename of the Phase 1 name.

**Tests: 172 passing, 0 skipped** (+21).
- Unit: `SearchTypeTest` (3), `SearchDocumentsTest` (5).
- Integration:
  - `SearchIT` (10): "beatls" → The Beatlz; rename searchable within 5 s; READY-only tracks with hydrated hits and
    removal on delete; artist/album renames propagated into tracks; grouping, types, per-type cursor paging;
    validation (blank/long q, bad type, cursor with several types, bad cursor) and 401; public vs private playlists
    with owner names; 429 rate limit with `Retry-After`; suggest p95 < 100 ms; reindex (403 listener / 202 admin)
  - `CatalogCacheIT` (3): cache hit and TTL, eviction on artist/track/artist-rename changes, 404s not cached

**Assumptions:** D58–D67.

**Environment note:** the repo is on an iCloud-synced Desktop. iCloud restored deleted build files under `target/`
as "name 2" conflict copies during builds (a duplicate `V1__… 2.sql` and missing classes), and the VS Code Java
extension also compiled into `target/classes`. Its auto-build is now off in `.vscode/settings.json` (local only).
Verification builds ran on an identical copy of the tree outside iCloud.

## Slice 2.2 — Activity, play counts and home

**Plan**
- Migration `V5__activity.sql`: `play_events` (one row per playback, keyed by a client-chosen `playId`),
  `listening_history` (read model: user, track, last played), `track_stats` (read model: plays and unique listeners
  over 30 days).
- `cadence-events`: `TrackPlayedPayload` (`playId`, `msPlayed`, `completed`, `skipped`, `source`, `sourceId`) with the
  30-second stream rule.
- `activity` context:
  - `POST /activity/plays`: the client reports one playback at 30 s and again on completion or skip with the same
    `playId`. Reports merge (max `msPlayed`, sticky flags), so retries and duplicates are idempotent. Each real change
    updates listening history and writes `activity.track-played` to the outbox (key: user id).
  - `GET /me/recently-played` (last 50 distinct tracks, newest first), `GET /me/top/tracks?range=short|medium|long`
    (counted plays in 4 weeks / 6 months / all time).
  - `GET /home`: shelves *Recently played*, *Your top tracks*, *Popular right now* (track stats) and *New releases*.
  - Scheduled `track_stats` refresh (every 5 min).
- `catalog`: consumes `activity.track-played` and increments `play_count` once per play that reaches 30 s, deduplicated
  by `playId` in `processed_event` within the same transaction. New `CatalogQueries.newReleases`.
- Tests: unit (play merge rules, ranges). `ActivityIT`: 30 s rule; exactly-once play count under duplicate HTTP and
  duplicate Kafka delivery; recently played (52 tracks + a replay → 50 distinct, in order); top tracks per range; home
  shelves; stats; validation/401/404/409. `cadence-e2e` `Phase2AcceptanceIT` for spec 9 Phase 2 AC1–AC4 against the
  packaged jars.

**Built**
- `cadence-events`: `TrackPlayedPayload` (30-second `STREAM_THRESHOLD_MS`).
- Migration `V5__activity.sql`: `play_events`, `listening_history`, `track_stats`.
- `activity` context:
  - Domain: `PlayEvent` (merge rules), `PlaySource`, `TopRange`
  - Infrastructure: `PlayEventRepository` (race-free insert, row lock, top-tracks aggregate), `ListeningHistory` and
    `TrackStatsStore` (JdbcClient read models)
  - Application: `PlayService`, `ActivityQueries`, `HomeService`, `TrackStatsService` (scheduled rebuild),
    `ActivityEvents` (outbox)
  - API: `ActivityController` (`POST /activity/plays`, `GET /me/recently-played`, `GET /me/top/tracks`),
    `HomeController` (`GET /home`)
- `catalog`: `PlayCountService` + `PlayCountListener` (play count +1 per playback reaching 30 s, deduplicated by
  playId), `AlbumSummary`, `CatalogQueries.newReleases`.
- `cadence-e2e`: `Phase2AcceptanceIT` (AC1–AC4 against the packaged jars and the real transcoder).

**Tests: 192 passing, 0 skipped** (+20).
- Unit: `PlayEventTest` (5), `TopRangeTest` (2).
- Integration: `ActivityIT` (9): exactly-once play count under a client retry, a completion report and duplicate
  Kafka delivery; plays under 30 s not counted; envelope contents; 52 tracks + replay → last 50 distinct in order;
  top tracks per range with paging; home shelves (incl. future releases excluded, empty shelves omitted); 30-day stats
  and expiry; validation/404/409/401; reports without playId.
- Acceptance: `Phase2AcceptanceIT` (4): fuzzy "beatls", album rename searchable after 984 ms, play counted once despite
  retry and Kafka redelivery, recently played (51 uploaded tracks) in order.

**Assumptions:** D68–D75.
