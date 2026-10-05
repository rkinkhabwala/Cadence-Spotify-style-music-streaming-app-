package com.cadence.events;

/** Values of {@link EventEnvelope#itemType()}, aligned with the recommender's multi-domain model (spec 3.5). */
public final class ItemTypes {

    public static final String SONG = "song";
    public static final String ARTIST = "artist";
    public static final String ALBUM = "album";
    public static final String PLAYLIST = "playlist";

    private ItemTypes() {
    }
}
