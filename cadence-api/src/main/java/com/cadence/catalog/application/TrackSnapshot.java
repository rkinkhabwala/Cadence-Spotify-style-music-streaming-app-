package com.cadence.catalog.application;

import com.cadence.catalog.CatalogRefs.AlbumRef;
import com.cadence.catalog.CatalogRefs.ArtistCredit;
import com.cadence.catalog.TrackStatus;
import com.cadence.catalog.TrackSummary;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Snapshot of a track in {@code catalog.entity-changed}: the {@link TrackSummary} fields plus the album's genres and
 * release date, so the event alone is enough for the recommender's catalog item (D88). Consumers that only need a
 * {@code TrackSummary} (search) read it as one and ignore the extra fields.
 */
public record TrackSnapshot(
        UUID id,
        String title,
        Integer durationMs,
        boolean explicit,
        int discNumber,
        int trackNumber,
        TrackStatus status,
        long playCount,
        AlbumRef album,
        List<ArtistCredit> artists,
        List<String> genres,
        LocalDate releaseDate) {

    static TrackSnapshot of(TrackSummary track, List<String> genres, LocalDate releaseDate) {
        return new TrackSnapshot(track.id(), track.title(), track.durationMs(), track.explicit(), track.discNumber(),
                track.trackNumber(), track.status(), track.playCount(), track.album(), track.artists(),
                genres == null ? List.of() : genres, releaseDate);
    }
}
