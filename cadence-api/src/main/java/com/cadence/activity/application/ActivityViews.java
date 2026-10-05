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

    /**
     * A recommended track. {@code reason}: the recommender's reason code, or POPULAR_IN_YOUR_GENRES / POPULAR from the
     * fallback. {@code position}: the slot in the recommender's list; send it back with the play report.
     */
    public record RecommendedTrack(TrackSummary track, String reason, int position) {
    }

    /**
     * {@code source}: {@code recommender} or {@code fallback}. {@code recommendationId}: the recommender's id for this
     * list (null from the fallback); send it with play reports for attribution. Not paginated: {@code nextCursor}
     * is always null.
     */
    public record Recommendations(List<RecommendedTrack> items, String source, String recommendationId,
                                  String nextCursor) {
    }

    /**
     * A shelf item is a track or an album, named by {@code type}; the other field is omitted. {@code position} is set
     * on recommended tracks (see {@link RecommendedTrack}).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ShelfItem(String type, TrackSummary track, AlbumSummary album, Integer position) {

        static ShelfItem of(TrackSummary track) {
            return new ShelfItem("track", track, null, null);
        }

        static ShelfItem of(AlbumSummary album) {
            return new ShelfItem("album", null, album, null);
        }

        static ShelfItem of(RecommendedTrack recommended) {
            return new ShelfItem("track", recommended.track(), null, recommended.position());
        }
    }

    /** {@code source} and {@code recommendationId} are set on recommendation shelves only. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Shelf(String id, String title, List<ShelfItem> items, String source, String recommendationId) {

        Shelf(String id, String title, List<ShelfItem> items) {
            this(id, title, items, null, null);
        }
    }

    /** Only non-empty shelves are returned, in display order. */
    public record Home(List<Shelf> shelves) {
    }
}
