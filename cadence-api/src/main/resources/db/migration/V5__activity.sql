-- Owner: activity. user/track/source ids reference other contexts by value (no FKs, D6).

-- One row per playback. The id is the client-chosen playId, so repeated reports of the same playback merge.
CREATE TABLE play_events (
    id         uuid PRIMARY KEY,
    user_id    uuid        NOT NULL,
    track_id   uuid        NOT NULL,
    started_at timestamptz NOT NULL,
    ms_played  integer     NOT NULL CHECK (ms_played >= 0),
    source     varchar(16) NOT NULL CHECK (source IN ('PLAYLIST', 'ALBUM', 'SEARCH', 'RADIO', 'ARTIST', 'LIBRARY', 'OTHER')),
    source_id  uuid,
    completed  boolean     NOT NULL DEFAULT false,
    skipped    boolean     NOT NULL DEFAULT false,
    counted_at timestamptz,                -- when ms_played first reached 30 s: the play counts as a stream
    updated_at timestamptz NOT NULL
);
CREATE INDEX ix_play_events_user_counted ON play_events (user_id, counted_at DESC) WHERE counted_at IS NOT NULL;
CREATE INDEX ix_play_events_counted ON play_events (counted_at) WHERE counted_at IS NOT NULL;

-- Read model: the last time each user played each track (recently played).
CREATE TABLE listening_history (
    user_id        uuid        NOT NULL,
    track_id       uuid        NOT NULL,
    last_played_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, track_id)
);
CREATE INDEX ix_listening_history_recent ON listening_history (user_id, last_played_at DESC, track_id DESC);

-- Read model: rolling 30-day popularity, rebuilt periodically from play_events.
CREATE TABLE track_stats (
    track_id             uuid PRIMARY KEY,
    plays_30d            bigint      NOT NULL,
    unique_listeners_30d bigint      NOT NULL,
    refreshed_at         timestamptz NOT NULL
);
CREATE INDEX ix_track_stats_popular ON track_stats (plays_30d DESC, unique_listeners_30d DESC, track_id);
