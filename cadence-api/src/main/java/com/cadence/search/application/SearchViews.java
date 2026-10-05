package com.cadence.search.application;

import com.cadence.catalog.CatalogRefs.ArtistRef;
import com.cadence.catalog.TrackSummary;
import com.cadence.common.pagination.CursorPage;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class SearchViews {

    private SearchViews() {
    }

    public record ArtistHit(UUID id, String name, String imageUrl, boolean verified) {
    }

    public record AlbumHit(UUID id, String title, String type, LocalDate releaseDate, String coverUrl, ArtistRef artist) {
    }

    public record OwnerRef(UUID id, String displayName) {
    }

    public record PlaylistHit(UUID id, String name, String description, String coverUrl, int trackCount, OwnerRef owner) {
    }

    /** Results grouped by type; groups that were not requested are omitted. Each group pages on its own. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SearchResults(String query, CursorPage<TrackSummary> tracks, CursorPage<ArtistHit> artists,
                                CursorPage<AlbumHit> albums, CursorPage<PlaylistHit> playlists) {
    }

    /**
     * One suggestion. {@code subtitle}: artist names for a track, the artist for an album, null otherwise.
     * {@code type} is {@code track}, {@code artist}, {@code album} or {@code playlist}.
     */
    public record Suggestion(String type, UUID id, String text, String subtitle, String imageUrl) {
    }

    public record Suggestions(String query, List<Suggestion> items) {
    }
}
