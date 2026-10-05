package com.cadence.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Shared envelope for every Kafka event produced or consumed by Cadence (spec 3.5).
 * The same JSON shape is consumed by the external recommender; see {@code schemas/event-envelope.schema.json}.
 *
 * @param eventId    unique id, used by consumers for idempotency (UUIDv7)
 * @param eventType  e.g. {@code track-played}; see {@link EventTypes}
 * @param occurredAt UTC instant the fact happened
 * @param userId     acting user, or {@code null} for system events
 * @param itemType   {@code song}, {@code artist}, {@code album}, ... see {@link ItemTypes}
 * @param itemId     id of the item the event is about
 * @param payload    event-specific body; never {@code null} (empty object when absent)
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID userId,
        String itemType,
        UUID itemId,
        JsonNode payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(itemType, "itemType");
        Objects.requireNonNull(itemId, "itemId");
        if (payload == null || payload.isNull()) {
            payload = JsonNodeFactory.instance.objectNode();
        }
    }

    /** Creates a new envelope with a fresh UUIDv7 id, converting {@code payload} with {@code mapper}. */
    public static EventEnvelope create(String eventType, Instant occurredAt, UUID userId,
                                       String itemType, UUID itemId, Object payload, ObjectMapper mapper) {
        return create(UuidV7.generate(), eventType, occurredAt, userId, itemType, itemId, payload, mapper);
    }

    /** Same as above with a caller-chosen event id (e.g. when the id doubles as a job id). */
    public static EventEnvelope create(UUID eventId, String eventType, Instant occurredAt, UUID userId,
                                       String itemType, UUID itemId, Object payload, ObjectMapper mapper) {
        return new EventEnvelope(eventId, eventType, occurredAt, userId, itemType, itemId,
                payload == null ? null : toTree(payload, mapper));
    }

    /**
     * Serializes and re-parses instead of {@code valueToTree}, so numeric nodes have the same types as after
     * a Kafka round trip (a {@code long} 182000 becomes an IntNode) and envelopes compare equal.
     */
    private static JsonNode toTree(Object payload, ObjectMapper mapper) {
        try {
            return mapper.readTree(mapper.writeValueAsBytes(payload));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Cannot serialize payload " + payload.getClass().getSimpleName(), e);
        }
    }

    /** Converts the payload to a typed record. */
    public <T> T payloadAs(Class<T> type, ObjectMapper mapper) {
        try {
            return mapper.treeToValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Payload of " + eventType + " is not a " + type.getSimpleName(), e);
        }
    }

    public String toJson(ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event " + eventId, e);
        }
    }

    public static EventEnvelope fromJson(String json, ObjectMapper mapper) {
        try {
            return mapper.readValue(json, EventEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not a valid event envelope", e);
        }
    }
}
