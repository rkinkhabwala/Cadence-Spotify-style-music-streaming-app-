package com.cadence.library.domain;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A track in a playlist; order is the lexicographic order of {@link #getPosition()} (fractional index). */
@Entity
@Table(name = "playlist_tracks")
public class PlaylistTrack {

    @Embeddable
    public record Key(UUID playlistId, UUID trackId) {
    }

    @EmbeddedId
    private Key id;
    private String position;
    private UUID addedBy;
    private Instant addedAt;

    protected PlaylistTrack() {
    }

    public PlaylistTrack(UUID playlistId, UUID trackId, String position, UUID addedBy, Instant addedAt) {
        FractionalIndex.validateKey(position);
        this.id = new Key(playlistId, trackId);
        this.position = position;
        this.addedBy = addedBy;
        this.addedAt = addedAt;
    }

    public void moveTo(String position) {
        FractionalIndex.validateKey(position);
        this.position = position;
    }

    public UUID getTrackId() {
        return id.trackId();
    }

    public String getPosition() {
        return position;
    }

    public UUID getAddedBy() {
        return addedBy;
    }

    public Instant getAddedAt() {
        return addedAt;
    }
}
