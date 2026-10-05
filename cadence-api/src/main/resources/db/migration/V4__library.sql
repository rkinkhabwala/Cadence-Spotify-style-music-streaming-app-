-- Owner: library. owner/user/track/artist/album ids reference other contexts by value (no FKs, D6).

CREATE TABLE playlists (
    id            uuid PRIMARY KEY,
    owner_id      uuid          NOT NULL,
    name          varchar(100)  NOT NULL,
    description   varchar(300),
    cover_url     varchar(2048),
    visibility    varchar(10)   NOT NULL CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    collaborative boolean       NOT NULL DEFAULT false,
    track_count   integer       NOT NULL DEFAULT 0 CHECK (track_count BETWEEN 0 AND 10000),
    version       bigint        NOT NULL DEFAULT 0,
    created_at    timestamptz   NOT NULL,
    updated_at    timestamptz   NOT NULL
);
CREATE INDEX ix_playlists_owner ON playlists (owner_id, id DESC);

CREATE TABLE playlist_tracks (
    playlist_id uuid         NOT NULL REFERENCES playlists (id) ON DELETE CASCADE,
    track_id    uuid         NOT NULL,
    position    varchar(255) COLLATE "C" NOT NULL,   -- fractional index; byte order = playlist order
    added_by    uuid         NOT NULL,
    added_at    timestamptz  NOT NULL,
    PRIMARY KEY (playlist_id, track_id),
    CONSTRAINT uq_playlist_tracks_position UNIQUE (playlist_id, position)
);

CREATE TABLE liked_tracks (
    user_id  uuid        NOT NULL,
    track_id uuid        NOT NULL,
    liked_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, track_id)
);
CREATE INDEX ix_liked_tracks_recent ON liked_tracks (user_id, liked_at DESC, track_id DESC);

CREATE TABLE followed_artists (
    user_id     uuid        NOT NULL,
    artist_id   uuid        NOT NULL,
    followed_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, artist_id)
);
CREATE INDEX ix_followed_artists_artist ON followed_artists (artist_id);

CREATE TABLE saved_albums (
    user_id  uuid        NOT NULL,
    album_id uuid        NOT NULL,
    saved_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, album_id)
);
CREATE INDEX ix_saved_albums_recent ON saved_albums (user_id, saved_at DESC, album_id DESC);
