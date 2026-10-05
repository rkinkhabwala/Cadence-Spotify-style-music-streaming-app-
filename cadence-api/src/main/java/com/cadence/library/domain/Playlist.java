package com.cadence.library.domain;

import com.cadence.common.error.ConflictException;
import com.cadence.common.error.ForbiddenException;
import com.cadence.common.error.NotFoundException;
import com.cadence.events.UuidV7;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * A user's playlist. {@code version} (optimistic locking) changes on every metadata or track change, so it is
 * the playlist's ETag.
 */
@Entity
@Table(name = "playlists")
public class Playlist {

    public static final int MAX_TRACKS = 10_000;

    @Id
    private UUID id;
    private UUID ownerId;
    private String name;
    private String description;
    private String coverUrl;
    @Enumerated(EnumType.STRING)
    private Visibility visibility;
    private boolean collaborative;
    private int trackCount;
    @Version
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected Playlist() {
    }

    public Playlist(UUID ownerId, String name, String description, Visibility visibility, boolean collaborative, Instant now) {
        this.id = UuidV7.generate();
        this.ownerId = ownerId;
        this.name = name.strip();
        this.description = blankToNull(description);
        this.visibility = visibility == null ? Visibility.PRIVATE : visibility;
        this.collaborative = collaborative;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Private playlists are invisible (404) to everyone but their owner. */
    public void requireVisibleTo(UUID userId) {
        if (visibility == Visibility.PRIVATE && !ownerId.equals(userId)) {
            throw new NotFoundException("Playlist", id);
        }
    }

    /** Owner-only operations: 404 when the caller can't even see the playlist, otherwise 403. */
    public void requireOwner(UUID userId) {
        requireVisibleTo(userId);
        if (!ownerId.equals(userId)) {
            throw new ForbiddenException("Only the playlist owner can do this");
        }
    }

    /** Who may add, remove and reorder tracks (collaborators join in Phase 3). */
    public void requireEditor(UUID userId) {
        requireOwner(userId);
    }

    public void update(String name, String description, Visibility visibility, Boolean collaborative, Instant now) {
        if (name != null) {
            this.name = name.strip();
        }
        if (description != null) {
            this.description = blankToNull(description);
        }
        if (visibility != null) {
            this.visibility = visibility;
        }
        if (collaborative != null) {
            this.collaborative = collaborative;
        }
        this.updatedAt = now;
    }

    /** Records a track change; bumps the version even when the count is unchanged (reorder). */
    public void tracksChanged(int newTrackCount, Instant now) {
        if (newTrackCount > MAX_TRACKS) {
            throw new ConflictException("playlist-full", "A playlist can hold at most " + MAX_TRACKS + " tracks");
        }
        this.trackCount = newTrackCount;
        this.updatedAt = now;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public Visibility getVisibility() {
        return visibility;
    }

    public boolean isCollaborative() {
        return collaborative;
    }

    public int getTrackCount() {
        return trackCount;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
