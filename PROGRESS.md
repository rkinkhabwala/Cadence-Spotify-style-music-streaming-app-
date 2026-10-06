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

## Slice 2.3 — Web client

**Plan**
- `cadence-web` (React 19 + TypeScript + Vite + hls.js, TanStack Query, React Router), a Maven module built with
  `frontend-maven-plugin` (pinned Node, `npm ci`, typecheck + build + Vitest) so `./mvnw verify` covers it.
- App shell: sidebar (nav, your library), main view, persistent player bar, queue panel. Pages: log in, sign up,
  home (shelves), search (suggest-as-you-type, top result, grouped results), artist, album, playlist (rename, remove,
  reorder), liked songs, library, your top tracks, admin (create artist/album/track, upload audio with progress, live
  processing status, retry failed).
- Player: one audio element owned above the router (navigation can never interrupt it), hls.js with a native-HLS
  fallback, queue with up-next, shuffle and repeat off/all/one, seek, volume, Media Session keys, and play reports at
  30 s and on completion/skip with one `playId` per playback.
- Backend support: CORS for the web origin (spec 6), `GET /me/following/artists`, the owner name on playlist detail,
  a `web` container (nginx) in the Compose `app` profile.
- Tests: Vitest unit tests (queue, play tracker, API client refresh) and component tests (player bar, search
  suggestions, and **playback continuing across route changes**). API ITs for CORS and the new endpoints.
  `scripts/verify-web.sh` drives the real app in headless Chrome against the running stack: play, navigate, assert the
  audio kept playing without reloading.

**Built**
- `cadence-web` (Maven module, `frontend-maven-plugin`, Node v24.19.0):
  - `api/`: typed client (single-flight, cross-tab-locked refresh), endpoints, TanStack Query hooks
  - `player/`: `queue` state machine, `PlayTracker` (30 s / completion / skip reports), `AudioEngine` (hls.js light,
    native-HLS fallback, one recovery from expired segment URLs), `PlayerProvider` above the router
  - `components/`: app shell, sidebar with Your Library (playlists, saved albums, followed artists), top bar, player
    bar, queue panel, search box with suggestions, track list with row menu (queue, add to playlist, go to
    artist/album, remove), cards, generated artwork, slider, toasts
  - `pages/`: login/sign-up, home, search, artist, album, playlist (edit, visibility, remove, drag to reorder, delete),
    Liked Songs, your top tracks + recently played, catalog admin (3-step create + upload with progress, live
    processing table, retry)
  - `Dockerfile` + `nginx.conf` (static files + API proxy); Compose service `web` (profile `app`, :3000)
  - `scripts/verify-browser.mjs` + `scripts/verify-web.sh` (`make web-verify`); `make web`
- API: CORS for the web origins; `GET /me/following/artists`; `ownerName` on playlist detail;
  `CatalogQueries.findArtists`.

**Tests: 215 passing, 0 skipped** (+23).
- Web (Vitest, run by `./mvnw verify`): `queue.test` (8), `tracker.test` (4), `client.test` (3), `SearchBox.test` (2),
  `PlaybackAcrossNavigation.test` (3): AC5 (same audio element, no reload, through sidebar, player-bar and
  programmatic navigation and history, with the 30 s report afterwards); player controls; signed-out redirect.
- API: `WebClientSupportIT` (3): CORS allowed/rejected/exposed headers, followed-artists paging, playlist owner name.

**Verified manually** against the full stack (`make up`, `make app`, `make seed`):
- `make seed` renamed the Phase 1 fixture to "The Beatlz". The API created the search indices at startup and replayed
  40 events into them.
- `verify-web` in headless Chrome against both the container (:3000) and the Vite dev server (:5173): log in →
  typing "beatls" suggests The Beatlz → play → Home, Search, album (from the player bar), Liked Songs, Back. Playback
  continued with the same element and one load (PASS). This found one bug: nginx forwarded `X-Forwarded-Proto`
  without a port, so manifest URLs lost `:3000` (fixed in `nginx.conf`).
- `verify-player.sh`: `/dev/player.html` still plays and seeks (PASS).

**Assumptions:** D76–D85.

## Pre-Phase 3 — Refresh token in an HttpOnly cookie (security fix)

**Plan**
- API: register/login/refresh set the refresh token only as the `cadence_refresh` cookie (HttpOnly, SameSite=Strict,
  `Path=/api/v1/auth`, Secure except on loopback hosts); `/auth/refresh` and `/auth/logout` read the cookie; response
  bodies drop `refreshToken`. Rotation and reuse detection (D20) unchanged.
- CSRF for the cookie endpoints: required `X-Cadence-CSRF` header (forces a CORS preflight), `Sec-Fetch-Site` and
  `Origin` checks; CORS allows the header, credentials stay off.
- Web client: access token in memory only, nothing in Web Storage, `/auth/refresh` on page load restores the session.
- Tests: cookie attributes, the refresh token never in a JSON body (or in the OpenAPI document), lifecycle, reuse,
  missing cookie, CSRF rejections, preflight; unit tests for the cookie and the guard; client tests; e2e AC1.

**Built**
- `identity.api`: `RefreshCookies`, `RefreshCsrfGuard`, cookie-based `AuthController`; `TokenResponse` without
  `refreshToken`. `ForbiddenException(code, detail)`. CORS allows `X-Cadence-CSRF`.
- `cadence-web`: `api/client.ts` (in-memory access token, cookie refresh with the CSRF header, single-flight + Web
  Locks), `AuthProvider` restores the session on load, logout via the cookie.
- `verify-browser.mjs` now also checks the cookie attributes, that scripts can't read it, that Web Storage holds no
  token, and that a reload restores the session.
- D86 supersedes D80. README auth example updated.

**Tests: 225 passing, 0 skipped** (+10).
- Unit: `RefreshCookiesTest` (2), `RefreshCsrfGuardTest` (2).
- Integration: `AuthIT` 14 → 19: cookie attributes, refresh token never in a JSON body or the OpenAPI document,
  lifecycle with cookie clearing, reuse clears the cookie, missing/unknown cookie, body token ignored, CSRF
  rejections (no header, cross-site, foreign Origin), preflight. `EndpointFailurePathsIT`: logout without the header → 403.
- Web: `client.test` 3 → 4 (reload restore, no Web Storage, stop retrying after a rejected refresh).
- e2e `Phase1AcceptanceIT` AC1 refreshes through the cookie.

**Verified manually** against the rebuilt containers: through the nginx proxy on :3000, login sets the cookie,
refresh with only the cookie returns `{accessToken, expiresIn, tokenType}`, refresh without the header → 403, logout →
204, refresh afterwards → 401. `make web-verify` (headless Chrome, :3000): PASS, including the reload check.

**Assumptions:** D86.

## Slice 3.1 — Recommender integration

**Plan**
- Contract (D87, D88): the recommender's own spec (`~/Desktop/Recommendation engine/spec.md` and its code, read-only)
  serves `GET /v1/recommendations` (X-Api-Key), which fits. It has no consumer for Cadence's Kafka topics, no
  unlike/unfollow event types and no artist-level follow. So: `INTEGRATION.md` lists exactly what it must add,
  Cadence builds against a WireMock stub of that contract, and the fallback is the default
  (`CADENCE_RECOMMENDER_ENABLED=false`).
- Envelope alignment in `cadence-events`:
  - `track-played` payload gains `durationMs`, `sessionId`, `recommendationId` and `position`, which the recommender
    needs for completion %, skip position, sessions and attribution.
  - Track `entity-changed` snapshots gain `genres` and `releaseDate`, and album/artist changes re-emit their tracks,
    so each track event is a self-contained `CatalogItem`.
  - JSON schema updated.
- Migration `V6__activity_recommendation_context.sql`: `play_events.session_id`, `recommendation_id`, `rec_position`.
  `POST /activity/plays` accepts them.
- `activity`:
  - `RecommenderClient`: RestClient on the JDK HttpClient (D37), explicit 300 ms connect/read timeouts, explicit
    attempts (1 = no retries; never retries 429/503 or timeouts, `Retry-After` ignored), Resilience4j circuit breaker.
  - `RecommendationService`: over-fetch, hydrate from the catalog, drop non-READY and liked tracks, Redis cache 10 min
    (recommender results only, fail-open), fallback = popular tracks (TrackStats, then all-time play counts) from the
    genres the user streams most.
  - `GET /me/recommendations`; home shelves "Made for you" and "Because you listened to X".
- Public APIs: `CatalogQueries.genresOf` / `popularTracks`, `LibraryQueries.likedAmong`.
- Web: home shelves carry the `recommendationId`; play reports send `sessionId`, `recommendationId` and `position`.
- Compose/.env: `CADENCE_RECOMMENDER_*`.
- Tests: WireMock-backed `RecommendationIT` (contract request shape, ≥ 20 READY non-liked recommendations, filtering,
  cache, timeout, 503 + Retry-After with no hidden retry, circuit breaker, disabled → fallback, home shelves,
  validation/401). `RecommenderFeedIT`: a play, like and follow are on Kafka within 2 s in the aligned envelope and map
  to valid recommender events. Unit tests for the fallback ranking and the event mapping.

**Built**
- `INTEGRATION.md`: the final contract. What already fits (`GET /v1/recommendations`), and what the recommender must add:
  - G1: a bridge consuming Cadence's topics, with the exact event mapping
  - G2: UNLIKE/UNFOLLOW
  - G3: artist-level FOLLOW
  - G4: `cadence-net` and host-port remapping
- `cadence-events`:
  - `TrackPlayedPayload` gains `durationMs`, `sessionId`, `recommendationId`, `position`
  - schema `$defs` for the track-played and entity-changed payloads
  - `recommender.RecommenderMapping`: envelope → the recommender's `EventDto` / `CatalogItemDto`
- Migration `V6__activity_recommendation_context.sql`.
- `catalog`:
  - `TrackSnapshot` (summary + album genres + release date) in every track `entity-changed`
  - album/artist updates re-emit their tracks
  - `CatalogQueries.genresOf`, `popularTrackIds`, `popularTrackIdsInGenres`
- `library`: public `LibraryQueries.likedAmong`.
- `activity`:
  - `RecommenderClient` (JDK HttpClient, 300 ms timeouts, explicit attempts, Resilience4j circuit breaker + Micrometer
    metrics), `RecommenderProperties`, `RecommendationCache` (Redis, 10 min, fail-open)
  - `FallbackRanking` (domain), `RecommendationService`, `RecommendationController` (`GET /me/recommendations`)
  - `HomeService`: "Made for you" and "Because you listened to X", fetched in parallel
  - play reports carry `sessionId`/`recommendationId`/`position`
- `cadence-web`: per-tab `SESSION_ID` on every play report; recommendation shelves pass `recommendationId` + `position`.
- Config: `cadence.recommender.*`, `CADENCE_RECOMMENDER_*` in `.env.example`, in-network URL override in Compose.
  Dependencies: `resilience4j-circuitbreaker`, `resilience4j-micrometer`, `wiremock-standalone` (test).

**Tests: 249 passing, 0 skipped** (+24).
- `cadence-events`: `RecommenderMappingTest` (4): play_start/play_end/skip once per playback, likes/follows,
  retractions unmapped, catalog items, camelCase JSON, all checked against the recommender's `EventValidator` rules.
  `EventEnvelopeTest` +1 (payload schema ↔ record).
- API unit: `FallbackRankingTest` (3), `PlayEventTest` +1.
- `RecommendationIT` (10, WireMock stub of `GET /v1/recommendations`, the recommender enabled):
  - AC2: 22 plays → 27 recommendations, none liked, all READY
  - the request matches the contract (path, params, `X-Api-Key`, radio + seed)
  - caching: one call per user, TTL ≈ 600 s
  - a like after caching still filters
  - a 2 s delay is cut off at 300 ms and the fallback is served in < 1.5 s
  - 503 and 429 with `Retry-After: 30`: exactly one request, answered in < 2 s
  - the circuit opens after 5 failures and stops calling
  - an empty or all-liked answer → fallback
  - home shelves with recommendationId/positions, seed excluded
  - validation/401
- `RecommenderContractIT` (4):
  - play/like/follow on Kafka < 2 s after the request, mapping to valid recommender events (skip position, duration,
    session, attribution)
  - an album genre change re-emits a self-contained track item
  - the default (off) fallback ranks the user's genres first and never liked tracks
  - play-context validation
- `ActivityIT` home assertions now include the recommendation shelves. Web: `tracker.test` +1.

**Not verified against the real recommender:** it can't consume Cadence's events yet (INTEGRATION.md G1–G3). The
client is verified against the stub of its actual contract, and the fallback is the default.

**Assumptions:** D87–D92.

## Slice 3.2 — Free vs Premium and collaborative playlists

**Plan**
- Free plan (spec 4, D96):
  - Bitrate cap (already enforced since 1.4, D39).
  - `POST /playback/{trackId}/skip`: a rolling 6 skips per hour, enforced by a Redis sorted set + Lua script;
    idempotent per `playId`; the 7th is 429 `skip-limit-reached` with `Retry-After`; Premium unlimited.
  - `adSlot` placeholder on every 3rd playback start of a free user.
- Collaborative playlists (D94):
  - Migration `V7__library_playlist_collaborators.sql` (`playlist_collaborators`, `playlists.invite_token`).
  - Roles OWNER / COLLABORATOR / LISTENER.
  - Owner-managed invite link: `POST/DELETE /playlists/{id}/invite`.
  - Join with the token: `POST /playlists/{id}/collaborators`. List and remove/leave:
    `GET /playlists/{id}/collaborators`, `DELETE /playlists/{id}/collaborators/{userId}`.
  - Collaborators add/remove/reorder tracks. `/me/playlists` includes joined playlists, and the detail carries the
    caller's `role`.
- Optimistic locking + automatic retry (D95): track changes without `If-Match` re-run in a fresh transaction (up to 10
  attempts, jittered back-off) when they lose the race; with `If-Match` the client still gets 412.
- Web:
  - Free skips go through the skip endpoint (toast on 429); ad slot card before the track.
  - Collaborative toggle, copy invite link, join banner, editing for collaborators.
- Tests: `Playlist` role unit tests; `CollaborativePlaylistIT` (invite/join/roles/leave/revoke, 2 collaborators × 10
  concurrent adds + reorders with no lost writes and no errors, `If-Match` still 412); `FreePlanIT` (7th skip → 429
  with Retry-After, idempotent per playId, Premium unlimited, ad slot every 3rd start for free users only, 320 kbps
  refused for free users); web tests for the skip guard and the ad slot.

**Built**
- Migration `V7__library_playlist_collaborators.sql`.
- `library`:
  - `Playlist` roles (OWNER/COLLABORATOR/LISTENER), invite token (create/revoke/constant-time check)
  - `PlaylistCollaborator` + repository
  - `PlaylistService`: invite/join/list/remove/leave, `/me/playlists` incl. joined playlists, `role` +
    `collaboratorCount` on the detail, track changes run through a `TransactionTemplate` retry loop (D95)
  - `PlaylistController` endpoints
- `streaming`:
  - `FreePlanLimits` (rolling-hour skip window as a Redis sorted set + Lua; ad counter; fail-open)
  - `POST /playback/{trackId}/skip`
  - `adSlot` on `PlaybackStart`
  - `cadence.streaming.free-plan.*`
- `cadence-web`:
  - user-initiated "next" asks the skip endpoint first (toast with minutes left on 429)
  - "Ad break" line in the player bar before the track
  - `Retry-After` on `ApiError`
  - playlist page: Collaborative switch, Copy invite link, auto-join from the link, Leave, editing for collaborators

**Tests: 264 passing, 0 skipped** (+15).
- Unit: `PlaylistTest` (3): roles, editor/owner checks, invite lifecycle.
- `CollaborativePlaylistIT` (5):
  - invite → join (wrong token 403, idempotent) → collaborator adds/reorders/removes but can't rename, delete or invite
  - `/me/playlists` and the collaborator list
  - public listeners read-only
  - revoke, leave, remove, turning collaboration off
  - **AC5:** 2 collaborators × 10 concurrent adds then 10 concurrent moves: all 200, 20 tracks, 10 per user, version +30
  - stale `If-Match` still 412
- `FreePlanIT` (4):
  - **AC4:** the 7th skip in an hour → 429 `skip-limit-reached`, `Retry-After` ≈ 3,600 s; a retried playId isn't counted
  - the window rolls (old skips expire, `Retry-After` from the oldest in-window skip)
  - Premium unlimited, 401 unauthenticated
  - ad slot on every 3rd free start only
- `PlaylistIT`: 8 concurrent adds now all succeed (server-side retry). Web: `FreePlan.test` (3): refused skip keeps the
  track with a toast, allowed skip moves on, ad slot delays the load.

**Assumptions:** D93–D96.
