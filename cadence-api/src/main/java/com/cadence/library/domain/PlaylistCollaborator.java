package com.cadence.library.domain;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A user who joined a collaborative playlist through its invite link (D94). */
@Entity
@Table(name = "playlist_collaborators")
public class PlaylistCollaborator {

    @Embeddable
    public record Key(UUID playlistId, UUID userId) {
    }

    @EmbeddedId
    private Key id;
    private Instant joinedAt;

    protected PlaylistCollaborator() {
    }

    public UUID getUserId() {
        return id.userId();
    }

    public UUID getPlaylistId() {
        return id.playlistId();
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }
}
