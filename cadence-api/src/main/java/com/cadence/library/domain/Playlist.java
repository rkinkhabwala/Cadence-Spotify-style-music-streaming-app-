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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

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
    private String inviteToken;
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

    /** What a user may do with a playlist (D94). */
    public enum Role {
        /** Everything, including metadata, deletion and managing collaborators. */
        OWNER,
        /** Joined through the invite link while the playlist is collaborative: add, remove and reorder tracks. */
        COLLABORATOR,
        /** Can only read a PUBLIC playlist. */
        LISTENER
    }

    /**
     * @param joined whether the user is in the playlist's collaborator list
     * @throws NotFoundException for a PRIVATE playlist the user isn't part of (it is invisible to them)
     */
    public Role roleOf(UUID userId, boolean joined) {
        if (ownerId.equals(userId)) {
            return Role.OWNER;
        }
        if (joined && collaborative) {
            return Role.COLLABORATOR;
        }
        if (visibility == Visibility.PUBLIC) {
            return Role.LISTENER;
        }
        throw new NotFoundException("Playlist", id);
    }

    /** Private playlists are invisible (404) to everyone but their owner and collaborators. */
    public void requireVisibleTo(UUID userId, boolean joined) {
        roleOf(userId, joined);
    }

    /** Owner-only operations: 404 when the caller can't even see the playlist, otherwise 403. */
    public void requireOwner(UUID userId, boolean joined) {
        if (roleOf(userId, joined) != Role.OWNER) {
            throw new ForbiddenException("Only the playlist owner can do this");
        }
    }

    /** Who may add, remove and reorder tracks: the owner, and collaborators while the playlist is collaborative. */
    public void requireEditor(UUID userId, boolean joined) {
        if (roleOf(userId, joined) == Role.LISTENER) {
            throw new ForbiddenException("Only the owner and collaborators can edit this playlist");
        }
    }

    /**
     * The invite link's token, created on first use (idempotent).
     *
     * @throws ConflictException ({@code not-collaborative}) unless the playlist is collaborative
     */
    public String invite(Supplier<String> newToken) {
        if (!collaborative) {
            throw new ConflictException("not-collaborative", "Make the playlist collaborative before inviting people");
        }
        if (inviteToken == null) {
            inviteToken = newToken.get();
        }
        return inviteToken;
    }

    /** The old link stops working; a new one can be created. */
    public void revokeInvite() {
        inviteToken = null;
    }

    /** Constant-time check of an invite token; false unless the playlist is collaborative with a live link. */
    public boolean acceptsInvite(String token) {
        return collaborative && inviteToken != null && token != null
                && MessageDigest.isEqual(inviteToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
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
            if (!collaborative) {
                this.inviteToken = null;
            }
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

    public String getInviteToken() {
        return inviteToken;
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
