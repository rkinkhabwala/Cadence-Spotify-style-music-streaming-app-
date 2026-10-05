package com.cadence.library.domain;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saved_albums")
public class SavedAlbum {

    @Embeddable
    public record Key(UUID userId, UUID albumId) {
    }

    @EmbeddedId
    private Key id;
    private Instant savedAt;

    protected SavedAlbum() {
    }

    public UUID getAlbumId() {
        return id.albumId();
    }

    public Instant getSavedAt() {
        return savedAt;
    }
}
