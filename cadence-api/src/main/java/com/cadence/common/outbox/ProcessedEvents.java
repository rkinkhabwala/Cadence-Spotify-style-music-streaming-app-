package com.cadence.common.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Idempotent-consumer guard. Call {@link #markProcessed} first inside the consumer's transaction and skip
 * the work when it returns {@code false}; a rollback of the work also rolls back the mark.
 */
@Component
public class ProcessedEvents {

    private final JdbcClient jdbc;

    public ProcessedEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return {@code true} the first time this consumer sees the event, {@code false} for duplicates */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(String consumer, UUID eventId) {
        return jdbc.sql("""
                        INSERT INTO processed_event (consumer, event_id) VALUES (:consumer, :eventId)
                        ON CONFLICT DO NOTHING
                        """)
                .param("consumer", consumer)
                .param("eventId", eventId)
                .update() == 1;
    }
}
