package com.cadence.library.domain;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "followed_artists")
public class FollowedArtist {

    @Embeddable
    public record Key(UUID userId, UUID artistId) {
    }

    @EmbeddedId
    private Key id;
    private Instant followedAt;

    protected FollowedArtist() {
    }

    public UUID getArtistId() {
        return id.artistId();
    }

    public Instant getFollowedAt() {
        return followedAt;
    }
}
