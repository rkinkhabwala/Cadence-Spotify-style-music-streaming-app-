package com.cadence.catalog.infrastructure.messaging;

import com.cadence.catalog.application.TranscodeResultService;
import com.cadence.events.EventEnvelope;
import com.cadence.events.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class TranscodeResultListener {

    private final TranscodeResultService results;
    private final ObjectMapper objectMapper;

    TranscodeResultListener(TranscodeResultService results, ObjectMapper objectMapper) {
        this.results = results;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = {Topics.STREAMING_TRACK_TRANSCODED, Topics.STREAMING_TRACK_TRANSCODE_FAILED},
            groupId = "cadence-api.catalog")
    void onResult(String message) {
        results.apply(EventEnvelope.fromJson(message, objectMapper));
    }
}
