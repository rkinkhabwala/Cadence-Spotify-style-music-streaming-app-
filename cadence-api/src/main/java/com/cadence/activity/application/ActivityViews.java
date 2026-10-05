package com.cadence.activity.application;

import com.cadence.catalog.AlbumSummary;
import com.cadence.catalog.TrackSummary;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ActivityViews {

    private ActivityViews() {
    }

    /** State of a playback after a report; {@code counted} = it has reached 30 s and counts as a stream. */
    public record PlayView(UUID playId, UUID trackId, int msPlayed, boolean completed, boolean skipped, boolean counted,
                           Instant startedAt) {
    }

    /** {@code playable} is false unless the track is READY (it may have been unpublished since). */
    public record RecentlyPlayedItem(TrackSummary track, boolean playable, Instant playedAt) {
    }

    /** {@code plays}: counted plays (≥ 30 s) in the requested range. */
    public record TopTrackItem(TrackSummary track, long plays) {
    }

    /** A shelf item is a track or an album, named by {@code type}; the other field is omitted. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ShelfItem(String type, TrackSummary track, AlbumSummary album) {

        static ShelfItem of(TrackSummary track) {
            return new ShelfItem("track", track, null);
        }

        static ShelfItem of(AlbumSummary album) {
            return new ShelfItem("album", null, album);
        }
    }

    public record Shelf(String id, String title, List<ShelfItem> items) {
    }

    /** Only non-empty shelves are returned, in display order. */
    public record Home(List<Shelf> shelves) {
    }
}
