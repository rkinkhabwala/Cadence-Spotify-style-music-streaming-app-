package com.cadence.events;

/** Values of {@link EventEnvelope#eventType()}. */
public final class EventTypes {

    public static final String TRACK_UPLOADED = "track-uploaded";
    public static final String TRACK_TRANSCODED = "track-transcoded";
    public static final String TRACK_TRANSCODE_FAILED = "track-transcode-failed";
    public static final String TRACK_PLAYED = "track-played";
    public static final String TRACK_LIKED = "track-liked";
    public static final String ARTIST_FOLLOWED = "artist-followed";
    public static final String ENTITY_CHANGED = "entity-changed";

    private EventTypes() {
    }
}
