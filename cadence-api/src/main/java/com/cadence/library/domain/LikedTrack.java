package com.cadence.library.domain;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "liked_tracks")
public class LikedTrack {

    @Embeddable
    public record Key(UUID userId, UUID trackId) {
    }

    @EmbeddedId
    private Key id;
    private Instant likedAt;

    protected LikedTrack() {
    }

    public UUID getTrackId() {
        return id.trackId();
    }

    public Instant getLikedAt() {
        return likedAt;
    }
}
