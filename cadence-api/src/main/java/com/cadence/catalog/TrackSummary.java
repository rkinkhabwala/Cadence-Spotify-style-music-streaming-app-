package com.cadence.catalog;

import com.cadence.catalog.CatalogRefs.AlbumRef;
import com.cadence.catalog.CatalogRefs.ArtistCredit;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.UUID;

/** Track as shown in lists (album tracklists, playlists, likes, search, home shelves). */
public record TrackSummary(
        UUID id,
        String title,
        Integer durationMs,
        boolean explicit,
        int discNumber,
        int trackNumber,
        TrackStatus status,
        long playCount,
        AlbumRef album,
        List<ArtistCredit> artists) {

    @JsonIgnore
    public boolean isPlayable() {
        return status == TrackStatus.READY;
    }
}
