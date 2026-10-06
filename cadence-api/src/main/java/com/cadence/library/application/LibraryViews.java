package com.cadence.library.application;

import com.cadence.catalog.CatalogRefs.AlbumRef;
import com.cadence.catalog.CatalogRefs.ArtistRef;
import com.cadence.catalog.TrackSummary;
import com.cadence.common.pagination.CursorPage;
import com.cadence.library.domain.Playlist;
import com.cadence.library.domain.Visibility;

import java.time.Instant;
import java.util.UUID;

public final class LibraryViews {

    private LibraryViews() {
    }

    public record PlaylistView(UUID id, UUID ownerId, String name, String description, String coverUrl,
                               Visibility visibility, boolean collaborative, int trackCount, long version,
                               Instant createdAt, Instant updatedAt) {
    }

    /** {@code track} is {@code null} if it was deleted from the catalog; {@code playable} is false unless READY. */
    public record PlaylistItem(UUID trackId, TrackSummary track, boolean playable, UUID addedBy, Instant addedAt) {
    }

    /**
     * {@code ownerName} is the owner's current display name; {@code role} is the caller's (OWNER, COLLABORATOR or
     * LISTENER: only the first two may edit tracks).
     */
    public record PlaylistDetail(UUID id, UUID ownerId, String ownerName, String name, String description, String coverUrl,
                                 Visibility visibility, boolean collaborative, int trackCount, long version,
                                 Instant createdAt, Instant updatedAt, Playlist.Role role, int collaboratorCount,
                                 CursorPage<PlaylistItem> tracks) {
    }

    public record CollaboratorView(UUID userId, String displayName, Instant joinedAt) {
    }

    public record LikedTrackView(TrackSummary track, Instant likedAt) {
    }

    public record SavedAlbumView(AlbumRef album, Instant savedAt) {
    }

    public record FollowedArtistView(ArtistRef artist, Instant followedAt) {
    }
}
