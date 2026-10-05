package com.cadence.common.outbox;

import com.cadence.events.EventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends an event to the transactional outbox. Must be called inside the transaction that changes the
 * state the event describes, so the write and the event commit (or roll back) together.
 */
@Component
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public OutboxWriter(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, String key, EventEnvelope envelope) {
        jdbc.sql("""
                        INSERT INTO outbox_event (id, topic, event_key, event_type, payload)
                        VALUES (:id, :topic, :key, :eventType, CAST(:payload AS jsonb))
                        """)
                .param("id", envelope.eventId())
                .param("topic", topic)
                .param("key", key)
                .param("eventType", envelope.eventType())
                .param("payload", envelope.toJson(objectMapper))
                .update();
    }
}
