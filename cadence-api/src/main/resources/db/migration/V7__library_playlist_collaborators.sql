-- Slice 3.2 (D94): collaborative playlists. The owner shares an invite link (invite_token); users who join with it
-- become collaborators and may add, remove and reorder tracks while the playlist is collaborative.
ALTER TABLE playlists ADD COLUMN invite_token varchar(64);
CREATE UNIQUE INDEX uq_playlists_invite_token ON playlists (invite_token) WHERE invite_token IS NOT NULL;

CREATE TABLE playlist_collaborators (
    playlist_id uuid        NOT NULL REFERENCES playlists (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL,
    joined_at   timestamptz NOT NULL,
    PRIMARY KEY (playlist_id, user_id)
);
-- "/me/playlists" lists the playlists a user collaborates on
CREATE INDEX ix_playlist_collaborators_user ON playlist_collaborators (user_id, playlist_id DESC);
