package com.cadence.library.application;

import com.cadence.common.outbox.OutboxWriter;
import com.cadence.events.EntityChangedPayload;
import com.cadence.events.EntityChangedPayload.Action;
import com.cadence.events.EventEnvelope;
import com.cadence.events.EventTypes;
import com.cadence.events.ItemTypes;
import com.cadence.events.Topics;
import com.cadence.library.application.LibraryViews.PlaylistView;
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

    /**
     * {@code library.playlist-changed}: the playlist's snapshot ({@code null} on delete), keyed by playlist id so
     * changes to one playlist stay ordered. Search indexes PUBLIC playlists from it.
     */
    void playlistChanged(PlaylistView playlist, UUID playlistId, Action action, Instant at) {
        EntityChangedPayload payload = new EntityChangedPayload(action,
                playlist == null ? null : objectMapper.valueToTree(playlist));
        outbox.append(Topics.LIBRARY_PLAYLIST_CHANGED, playlistId.toString(), EventEnvelope.create(
                EventTypes.ENTITY_CHANGED, at, playlist == null ? null : playlist.ownerId(), ItemTypes.PLAYLIST,
                playlistId, payload, objectMapper));
    }

    private void append(String topic, String eventType, UUID userId, String itemType, UUID itemId, Instant at) {
        outbox.append(topic, userId.toString(),
                EventEnvelope.create(eventType, at, userId, itemType, itemId, null, objectMapper));
    }
}
