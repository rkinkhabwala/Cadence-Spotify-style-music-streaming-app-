package com.cadence.support;

import com.cadence.events.CadenceJackson;
import com.cadence.events.EventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Reads Cadence topics from their current end, like an outside subscriber (the recommender's bridge) would.
 * Records the arrival time of every envelope.
 */
public class KafkaProbe implements AutoCloseable {

    public record Received(EventEnvelope event, String key, Instant arrivedAt) {
    }

    private static final ObjectMapper JSON = CadenceJackson.newObjectMapper();
    private final KafkaConsumer<String, String> consumer;
    private final List<Received> received = new ArrayList<>();

    public KafkaProbe(String bootstrapServers, String... topics) {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
        List<TopicPartition> partitions = new ArrayList<>();
        for (String topic : topics) {
            consumer.partitionsFor(topic).forEach(p -> partitions.add(new TopicPartition(topic, p.partition())));
        }
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        partitions.forEach(consumer::position); // resolve the end offsets now, before the test produces anything
    }

    /** Polls until an envelope matching {@code match} arrives or {@code timeout} passes. */
    public Received await(Predicate<EventEnvelope> match, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            for (Received r : received) {
                if (match.test(r.event())) {
                    return r;
                }
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("No matching event within " + timeout + "; received " + received.size());
            }
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(50))) {
                received.add(new Received(EventEnvelope.fromJson(record.value(), JSON), record.key(), Instant.now()));
            }
        }
    }

    @Override
    public void close() {
        consumer.close();
    }
}
