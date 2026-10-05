package com.cadence.events;

import java.util.List;

/** Kafka topic names (spec 3.4). */
public final class Topics {

    public static final String CATALOG_TRACK_UPLOADED = "catalog.track-uploaded";
    public static final String CATALOG_ENTITY_CHANGED = "catalog.entity-changed";
    public static final String STREAMING_TRACK_TRANSCODED = "streaming.track-transcoded";
    public static final String STREAMING_TRACK_TRANSCODE_FAILED = "streaming.track-transcode-failed";
    public static final String ACTIVITY_TRACK_PLAYED = "activity.track-played";
    public static final String LIBRARY_TRACK_LIKED = "library.track-liked";
    public static final String LIBRARY_ARTIST_FOLLOWED = "library.artist-followed";

    public static final List<String> ALL = List.of(
            CATALOG_TRACK_UPLOADED,
            CATALOG_ENTITY_CHANGED,
            STREAMING_TRACK_TRANSCODED,
            STREAMING_TRACK_TRANSCODE_FAILED,
            ACTIVITY_TRACK_PLAYED,
            LIBRARY_TRACK_LIKED,
            LIBRARY_ARTIST_FOLLOWED);

    private Topics() {
    }
}
