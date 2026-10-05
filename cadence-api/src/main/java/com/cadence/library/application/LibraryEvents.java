package com.cadence.library.application;

import com.cadence.common.outbox.OutboxWriter;
import com.cadence.events.EventEnvelope;
import com.cadence.events.EventTypes;
import com.cadence.events.ItemTypes;
import com.cadence.events.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** {@code library.*} events in the shared envelope, keyed by user id (spec 3.4), written to the outbox. */
@Component
class LibraryEvents {

    private final OutboxWriter outbox;
    private final ObjectMapper objectMapper;

    LibraryEvents(OutboxWriter outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    void trackLiked(UUID userId, UUID trackId, boolean liked, Instant at) {
        append(Topics.LIBRARY_TRACK_LIKED, liked ? EventTypes.TRACK_LIKED : EventTypes.TRACK_UNLIKED, userId,
                ItemTypes.SONG, trackId, at);
    }

    void artistFollowed(UUID userId, UUID artistId, boolean followed, Instant at) {
        append(Topics.LIBRARY_ARTIST_FOLLOWED, followed ? EventTypes.ARTIST_FOLLOWED : EventTypes.ARTIST_UNFOLLOWED,
                userId, ItemTypes.ARTIST, artistId, at);
    }

    private void append(String topic, String eventType, UUID userId, String itemType, UUID itemId, Instant at) {
        outbox.append(topic, userId.toString(),
                EventEnvelope.create(eventType, at, userId, itemType, itemId, null, objectMapper));
    }
}
