package com.cadence.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Publishes pending outbox rows to Kafka in insertion order. Rows are claimed with
 * {@code FOR UPDATE SKIP LOCKED}, so several API instances can poll safely. A batch stops at the first
 * failed send so later events for the same key are not published ahead of it (at-least-once delivery).
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxPoller(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                        OutboxProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.properties = properties;
        this.clock = clock;
    }

    private record PendingEvent(UUID id, String topic, String eventKey, String payload) {
    }

    @Scheduled(fixedDelayString = "${cadence.outbox.poll-interval}")
    public void poll() {
        int published;
        do {
            published = publishBatch();
        } while (published == properties.batchSize());
    }

    /** @return number of events published in this batch */
    public int publishBatch() {
        Integer count = tx.execute(status -> {
            List<PendingEvent> pending = jdbc.sql("""
                            SELECT id, topic, event_key, payload::text AS payload
                            FROM outbox_event
                            WHERE published_at IS NULL
                            ORDER BY seq
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED
                            """)
                    .param("limit", properties.batchSize())
                    .query(PendingEvent.class)
                    .list();
            int sent = 0;
            for (PendingEvent event : pending) {
                try {
                    kafka.send(event.topic(), event.eventKey(), event.payload())
                            .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    recordFailure(event, e);
                    break;
                } catch (Exception e) {
                    recordFailure(event, e);
                    break;
                }
                jdbc.sql("UPDATE outbox_event SET published_at = :now, attempts = attempts + 1 WHERE id = :id")
                        .param("now", utc(clock.instant()))
                        .param("id", event.id())
                        .update();
                sent++;
            }
            return sent;
        });
        return count == null ? 0 : count;
    }

    private void recordFailure(PendingEvent event, Exception e) {
        log.warn("Outbox publish failed for event {} on {}: {}", event.id(), event.topic(), e.toString());
        jdbc.sql("UPDATE outbox_event SET attempts = attempts + 1, last_error = :error WHERE id = :id")
                .param("error", e.toString())
                .param("id", event.id())
                .update();
    }

    /** Deletes published rows older than the retention period. */
    @Scheduled(cron = "0 17 * * * *")
    public void purgePublished() {
        Instant cutoff = clock.instant().minus(properties.retention().toMillis(), ChronoUnit.MILLIS);
        int deleted = jdbc.sql("DELETE FROM outbox_event WHERE published_at < :cutoff")
                .param("cutoff", utc(cutoff))
                .update();
        if (deleted > 0) {
            log.info("Purged {} published outbox events", deleted);
        }
    }

    /** pgjdbc binds OffsetDateTime but not Instant. */
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
