package com.cadence.catalog.infrastructure.messaging;

import com.cadence.catalog.application.PlayCountService;
import com.cadence.events.EventEnvelope;
import com.cadence.events.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class PlayCountListener {

    private final PlayCountService playCounts;
    private final ObjectMapper objectMapper;

    PlayCountListener(PlayCountService playCounts, ObjectMapper objectMapper) {
        this.playCounts = playCounts;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.ACTIVITY_TRACK_PLAYED, groupId = "cadence-api.catalog")
    void onPlay(String message) {
        playCounts.apply(EventEnvelope.fromJson(message, objectMapper));
    }
}
