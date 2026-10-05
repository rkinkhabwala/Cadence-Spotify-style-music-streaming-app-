package com.cadence.activity.application;

import com.cadence.activity.domain.PlayEvent;
import com.cadence.common.outbox.OutboxWriter;
import com.cadence.events.EventEnvelope;
import com.cadence.events.EventTypes;
import com.cadence.events.ItemTypes;
import com.cadence.events.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** {@code activity.track-played} in the shared envelope, keyed by user id (spec 3.4), written to the outbox. */
@Component
class ActivityEvents {

    private final OutboxWriter outbox;
    private final ObjectMapper objectMapper;

    ActivityEvents(OutboxWriter outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    void trackPlayed(PlayEvent play, Integer durationMs, Instant at) {
        outbox.append(Topics.ACTIVITY_TRACK_PLAYED, play.getUserId().toString(), EventEnvelope.create(
                EventTypes.TRACK_PLAYED, at, play.getUserId(), ItemTypes.SONG, play.getTrackId(),
                play.toPayload(durationMs), objectMapper));
    }
}
