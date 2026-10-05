package com.cadence.catalog.application;

import com.cadence.catalog.infrastructure.TrackRepository;
import com.cadence.common.outbox.ProcessedEvents;
import com.cadence.events.EventEnvelope;
import com.cadence.events.EventTypes;
import com.cadence.events.TrackPlayedPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains {@code tracks.play_count} from {@code activity.track-played} (spec 4: a play counts once it reaches 30 s).
 * One playback is reported several times and Kafka may redeliver any report, so the idempotency key is the playback
 * ({@code playId}), not the event id: the first report of a playback at or past 30 s increments the count, in the
 * same transaction that records the playId in {@code processed_event}; every later or repeated report is a no-op.
 * Play counts don't emit {@code catalog.entity-changed}: they change far too often for search to care.
 */
@Service
public class PlayCountService {

    static final String CONSUMER = "catalog.play-count";
    private static final Logger log = LoggerFactory.getLogger(PlayCountService.class);

    private final TrackRepository tracks;
    private final ProcessedEvents processedEvents;
    private final ObjectMapper objectMapper;

    PlayCountService(TrackRepository tracks, ProcessedEvents processedEvents, ObjectMapper objectMapper) {
        this.tracks = tracks;
        this.processedEvents = processedEvents;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void apply(EventEnvelope event) {
        if (!EventTypes.TRACK_PLAYED.equals(event.eventType())) {
            return;
        }
        TrackPlayedPayload play = event.payloadAs(TrackPlayedPayload.class, objectMapper);
        if (play.playId() == null) {
            throw new IllegalArgumentException("track-played event " + event.eventId() + " has no playId");
        }
        if (!play.isStream() || !processedEvents.markProcessed(CONSUMER, play.playId())) {
            return;
        }
        if (tracks.incrementPlayCount(event.itemId()) == 0) {
            log.info("Play of deleted track {} not counted", event.itemId());
        }
    }
}
