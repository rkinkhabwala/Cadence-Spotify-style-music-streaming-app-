package com.cadence.catalog.application;

import com.cadence.catalog.CatalogRefs.AlbumRef;
import com.cadence.catalog.CatalogRefs.ArtistCredit;
import com.cadence.catalog.CatalogRefs.ArtistRef;
import com.cadence.catalog.TrackStatus;
import com.cadence.catalog.TrackSummary;
import com.cadence.catalog.domain.AlbumType;
import com.cadence.catalog.domain.ArtistRole;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read models returned by catalog use cases (entities never leave the application layer). */
public final class CatalogViews {

    private CatalogViews() {
    }

    public record ArtistView(UUID id, String name, String bio, String imageUrl, boolean verified,
                             long monthlyListeners, Instant createdAt, Instant updatedAt) {
    }

    public record ArtistDetail(UUID id, String name, String bio, String imageUrl, boolean verified,
                               long monthlyListeners, List<TrackSummary> topTracks) {
    }

    public record AlbumView(UUID id, String title, AlbumType type, LocalDate releaseDate, String coverUrl,
                            String label, ArtistRef artist, List<String> genres, Instant createdAt, Instant updatedAt) {
    }

    public record AlbumDetail(UUID id, String title, AlbumType type, LocalDate releaseDate, String coverUrl,
                              String label, ArtistRef artist, List<String> genres, long totalDurationMs,
                              List<TrackSummary> tracks) {
    }

    public record TrackDetail(UUID id, String title, Integer durationMs, boolean explicit, int discNumber,
                              int trackNumber, String isrc, long playCount, AlbumRef album,
                              List<ArtistCredit> artists) {
    }

    public record CreditView(UUID artistId, ArtistRole role) {
    }

    public record AdminTrackView(UUID id, String title, UUID albumId, int discNumber, int trackNumber,
                                 boolean explicit, String isrc, TrackStatus status, Integer durationMs,
                                 Double loudnessLufs, String failureReason, String sourceKey,
                                 List<CreditView> artists, Instant createdAt, Instant updatedAt) {
    }

    public record GenreView(UUID id, String name) {
    }

    public record UploadUrl(URI uploadUrl, String method, Map<String, String> headers, String objectKey,
                            Instant expiresAt) {
    }
}
