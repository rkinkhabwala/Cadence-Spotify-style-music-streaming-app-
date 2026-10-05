-- Owner: catalog.

CREATE TABLE artists (
    id                uuid PRIMARY KEY,
    name              varchar(200)  NOT NULL,
    bio               varchar(5000),
    image_url         varchar(2048),
    verified          boolean       NOT NULL DEFAULT false,
    monthly_listeners bigint        NOT NULL DEFAULT 0,   -- denormalized, maintained by activity (Phase 2)
    created_at        timestamptz   NOT NULL,
    updated_at        timestamptz   NOT NULL,
    version           bigint        NOT NULL DEFAULT 0
);

CREATE TABLE albums (
    id           uuid PRIMARY KEY,
    title        varchar(300) NOT NULL,
    artist_id    uuid         NOT NULL REFERENCES artists (id),
    release_date date         NOT NULL,
    type         varchar(10)  NOT NULL CHECK (type IN ('ALBUM', 'SINGLE', 'EP')),
    cover_url    varchar(2048),
    label        varchar(200),
    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    version      bigint       NOT NULL DEFAULT 0
);
CREATE INDEX ix_albums_artist_release ON albums (artist_id, release_date DESC, id DESC);

CREATE TABLE genres (
    id   uuid PRIMARY KEY,
    name varchar(100) NOT NULL
);
CREATE UNIQUE INDEX uq_genres_name_ci ON genres (lower(name));

CREATE TABLE album_genres (
    album_id uuid NOT NULL REFERENCES albums (id) ON DELETE CASCADE,
    genre_id uuid NOT NULL REFERENCES genres (id),
    PRIMARY KEY (album_id, genre_id)
);

CREATE TABLE tracks (
    id               uuid PRIMARY KEY,
    title            varchar(300) NOT NULL,
    album_id         uuid         NOT NULL REFERENCES albums (id),
    track_number     integer      NOT NULL CHECK (track_number > 0),
    disc_number      integer      NOT NULL DEFAULT 1 CHECK (disc_number > 0),
    duration_ms      integer,
    explicit         boolean      NOT NULL DEFAULT false,
    status           varchar(16)  NOT NULL CHECK (status IN ('DRAFT', 'PROCESSING', 'READY', 'FAILED')),
    play_count       bigint       NOT NULL DEFAULT 0,
    isrc             varchar(12),
    source_key       varchar(512),             -- raw upload object key
    transcode_job_id uuid,                     -- event id of the current transcode job
    loudness_lufs    double precision,         -- EBU R128 integrated loudness
    failure_reason   varchar(2000),
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL,
    version          bigint       NOT NULL DEFAULT 0
);
CREATE INDEX ix_tracks_album ON tracks (album_id, disc_number, track_number);
CREATE INDEX ix_tracks_status_updated ON tracks (status, updated_at DESC, id DESC);
CREATE INDEX ix_tracks_updated ON tracks (updated_at DESC, id DESC);

CREATE TABLE track_artists (
    track_id  uuid        NOT NULL REFERENCES tracks (id) ON DELETE CASCADE,
    artist_id uuid        NOT NULL REFERENCES artists (id),
    role      varchar(16) NOT NULL CHECK (role IN ('PRIMARY', 'FEATURED')),
    PRIMARY KEY (track_id, artist_id)
);
CREATE INDEX ix_track_artists_artist ON track_artists (artist_id);
