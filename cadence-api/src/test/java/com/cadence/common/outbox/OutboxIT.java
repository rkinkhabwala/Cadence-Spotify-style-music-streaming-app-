package com.cadence.common.outbox;

import com.cadence.IntegrationTest;
import com.cadence.events.EventEnvelope;
import com.cadence.events.ItemTypes;
import com.cadence.events.UuidV7;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class OutboxIT extends IntegrationTest {

    private static final String TOPIC = "test.outbox";

    @Autowired
    OutboxWriter outbox;
    @Autowired
    ProcessedEvents processedEvents;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    ConsumerFactory<String, String> consumerFactory;

    private Consumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

    @BeforeEach
    void subscribe() {
        consumer = consumerFactory.createConsumer("outbox-it-" + UUID.randomUUID(), null);
        consumer.subscribe(List.of(TOPIC));
    }

    @AfterEach
    void close() {
        consumer.close();
    }

    @Test
    void committedEventIsPublishedToKafkaAndMarked() {
        EventEnvelope event = envelope();

        tx.executeWithoutResult(s -> outbox.append(TOPIC, event.itemId().toString(), event));

        ConsumerRecord<String, String> record = awaitRecord(event.eventId());
        assertThat(record.key()).isEqualTo(event.itemId().toString());
        assertThat(EventEnvelope.fromJson(record.value(), objectMapper)).isEqualTo(event);
        await().atMost(Duration.ofSeconds(5)).until(() -> jdbc
                .sql("SELECT published_at IS NOT NULL FROM outbox_event WHERE id = :id")
                .param("id", event.eventId()).query(Boolean.class).single());
    }

    @Test
    void rolledBackEventIsNeverPublished() {
        EventEnvelope rolledBack = envelope();
        EventEnvelope committedAfter = envelope();

        tx.executeWithoutResult(s -> {
            outbox.append(TOPIC, "k", rolledBack);
            s.setRollbackOnly();
        });
        tx.executeWithoutResult(s -> outbox.append(TOPIC, "k", committedAfter));

        awaitRecord(committedAfter.eventId());
        assertThat(received).noneMatch(r -> r.value().contains(rolledBack.eventId().toString()));
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_event WHERE id = :id")
                .param("id", rolledBack.eventId()).query(Long.class).single()).isZero();
    }

    @Test
    void appendRequiresAnExistingTransaction() {
        assertThatThrownBy(() -> outbox.append(TOPIC, "k", envelope()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void processedEventsDetectsDuplicatesPerConsumer() {
        UUID eventId = UuidV7.generate();

        Boolean first = tx.execute(s -> processedEvents.markProcessed("test-consumer", eventId));
        Boolean duplicate = tx.execute(s -> processedEvents.markProcessed("test-consumer", eventId));
        Boolean otherConsumer = tx.execute(s -> processedEvents.markProcessed("other-consumer", eventId));

        assertThat(first).isTrue();
        assertThat(duplicate).isFalse();
        assertThat(otherConsumer).isTrue();
    }

    @Test
    void processedMarkIsRolledBackWithTheConsumersWork() {
        UUID eventId = UuidV7.generate();

        tx.executeWithoutResult(s -> {
            processedEvents.markProcessed("test-consumer", eventId);
            s.setRollbackOnly();
        });

        Boolean afterRollback = tx.execute(s -> processedEvents.markProcessed("test-consumer", eventId));
        assertThat(afterRollback).isTrue();
    }

    private ConsumerRecord<String, String> awaitRecord(UUID eventId) {
        return await().atMost(Duration.ofSeconds(20)).until(() -> {
            consumer.poll(Duration.ofMillis(250)).forEach(received::add);
            return received.stream().filter(r -> r.value().contains(eventId.toString())).findFirst().orElse(null);
        }, r -> r != null);
    }

    private EventEnvelope envelope() {
        return new EventEnvelope(UuidV7.generate(), "test-event", Instant.parse("2026-10-05T12:00:00Z"),
                UuidV7.generate(), ItemTypes.SONG, UuidV7.generate(),
                JsonNodeFactory.instance.objectNode().put("n", 1));
    }
}
