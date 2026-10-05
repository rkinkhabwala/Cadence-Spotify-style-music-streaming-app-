package com.cadence.transcoder.messaging;

import com.cadence.events.EventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Used by the Kafka error handler when a job could not be processed even after retries. */
@Component
public class TranscodeFailurePublisher {

    private static final Logger log = LoggerFactory.getLogger(TranscodeFailurePublisher.class);

    private final ResultPublisher publisher;
    private final ObjectMapper objectMapper;

    public TranscodeFailurePublisher(ResultPublisher publisher, ObjectMapper objectMapper) {
        this.publisher = publisher;
        this.objectMapper = objectMapper;
    }

    public void publishForRecord(String value, String reason) {
        try {
            EventEnvelope job = EventEnvelope.fromJson(value, objectMapper);
            publisher.failed(job.itemId(), job.eventId(), reason);
        } catch (RuntimeException e) {
            log.error("Dropping unprocessable message (cannot report failure): {}", e.toString());
        }
    }
}
