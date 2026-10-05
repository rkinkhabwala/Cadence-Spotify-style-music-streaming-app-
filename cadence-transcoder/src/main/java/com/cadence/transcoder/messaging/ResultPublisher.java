package com.cadence.transcoder.messaging;

import com.cadence.events.EventEnvelope;
import com.cadence.events.EventTypes;
import com.cadence.events.ItemTypes;
import com.cadence.events.Topics;
import com.cadence.events.TrackTranscodeFailedPayload;
import com.cadence.events.TrackTranscodedPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes job results. The transcoder owns no database, so it sends directly (synchronously) instead of via an
 * outbox: the result is only published after the output is stored, and a redelivered job republishes it.
 */
@Component
public class ResultPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ResultPublisher(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.clock = Clock.systemUTC();
    }

    public void transcoded(UUID trackId, TrackTranscodedPayload result) {
        send(Topics.STREAMING_TRACK_TRANSCODED, trackId, EventTypes.TRACK_TRANSCODED, result);
    }

    public void failed(UUID trackId, UUID jobId, String reason) {
        send(Topics.STREAMING_TRACK_TRANSCODE_FAILED, trackId, EventTypes.TRACK_TRANSCODE_FAILED,
                new TrackTranscodeFailedPayload(jobId, reason));
    }

    private void send(String topic, UUID trackId, String eventType, Object payload) {
        EventEnvelope envelope = EventEnvelope.create(eventType, clock.instant(), null, ItemTypes.SONG, trackId,
                payload, objectMapper);
        try {
            kafka.send(topic, trackId.toString(), envelope.toJson(objectMapper)).get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing " + eventType, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Cannot publish " + eventType + " for track " + trackId, e);
        }
    }
}
