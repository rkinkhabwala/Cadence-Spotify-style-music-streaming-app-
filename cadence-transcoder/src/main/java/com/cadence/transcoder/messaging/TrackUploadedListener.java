package com.cadence.transcoder.messaging;

import com.cadence.events.EventEnvelope;
import com.cadence.events.Topics;
import com.cadence.transcoder.application.TranscodeJobHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes {@code catalog.track-uploaded} and hands each job to the {@link TranscodeJobHandler}. */
@Component
class TrackUploadedListener {

    private final TranscodeJobHandler handler;
    private final ObjectMapper objectMapper;

    TrackUploadedListener(TranscodeJobHandler handler, ObjectMapper objectMapper) {
        this.handler = handler;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.CATALOG_TRACK_UPLOADED)
    void onTrackUploaded(String message) {
        handler.handle(EventEnvelope.fromJson(message, objectMapper));
    }
}
