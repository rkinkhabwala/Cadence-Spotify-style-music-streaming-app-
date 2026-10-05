package com.cadence.search.infrastructure.messaging;

import com.cadence.events.EventEnvelope;
import com.cadence.events.Topics;
import com.cadence.search.application.SearchIndexer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class SearchEventsListener {

    private final SearchIndexer indexer;
    private final ObjectMapper objectMapper;

    SearchEventsListener(SearchIndexer indexer, ObjectMapper objectMapper) {
        this.indexer = indexer;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = {Topics.CATALOG_ENTITY_CHANGED, Topics.LIBRARY_PLAYLIST_CHANGED},
            groupId = "cadence-api.search", containerFactory = SearchKafkaConfig.FACTORY)
    void onChange(String message) {
        indexer.apply(EventEnvelope.fromJson(message, objectMapper));
    }
}
