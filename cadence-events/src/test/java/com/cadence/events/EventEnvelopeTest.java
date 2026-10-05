package com.cadence.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEnvelopeTest {

    private final ObjectMapper mapper = CadenceJackson.newObjectMapper();

    record PlayedPayload(long msPlayed, boolean completed, boolean skipped, String source) {
    }

    @Test
    void serializesToTheSpecShapeAndBack() throws Exception {
        UUID userId = UuidV7.generate();
        UUID trackId = UuidV7.generate();
        EventEnvelope envelope = EventEnvelope.create(EventTypes.TRACK_PLAYED, Instant.parse("2026-10-05T15:42:00Z"),
                userId, ItemTypes.SONG, trackId, new PlayedPayload(182_000, true, false, "PLAYLIST"), mapper);

        String json = envelope.toJson(mapper);
        JsonNode tree = mapper.readTree(json);

        assertThat(tree.get("occurredAt").asText()).isEqualTo("2026-10-05T15:42:00Z");
        assertThat(tree.get("eventType").asText()).isEqualTo("track-played");
        assertThat(tree.get("itemType").asText()).isEqualTo("song");
        assertThat(tree.get("userId").asText()).isEqualTo(userId.toString());
        assertThat(tree.at("/payload/msPlayed").asLong()).isEqualTo(182_000);

        EventEnvelope back = EventEnvelope.fromJson(json, mapper);
        assertThat(back).isEqualTo(envelope);
        assertThat(back.payloadAs(PlayedPayload.class, mapper))
                .isEqualTo(new PlayedPayload(182_000, true, false, "PLAYLIST"));
    }

    @Test
    void toleratesUnknownFieldsFromNewerProducers() {
        String json = """
                {"eventId":"%s","eventType":"track-liked","occurredAt":"2026-10-05T15:42:00Z",
                 "userId":null,"itemType":"song","itemId":"%s","payload":{},"schemaVersion":2}
                """.formatted(UuidV7.generate(), UuidV7.generate());

        EventEnvelope envelope = EventEnvelope.fromJson(json, mapper);

        assertThat(envelope.userId()).isNull();
        assertThat(envelope.eventType()).isEqualTo(EventTypes.TRACK_LIKED);
    }

    @Test
    void missingPayloadBecomesEmptyObject() {
        EventEnvelope envelope = new EventEnvelope(UuidV7.generate(), "x", Instant.now(), null,
                ItemTypes.ARTIST, UuidV7.generate(), null);

        assertThat(envelope.payload().isObject()).isTrue();
        assertThat(envelope.payload().isEmpty()).isTrue();
    }

    @Test
    void rejectsMissingRequiredFields() {
        assertThatThrownBy(() -> new EventEnvelope(null, "x", Instant.now(), null, "song", UuidV7.generate(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("eventId");
        assertThatThrownBy(() -> EventEnvelope.fromJson("{\"eventType\":\"x\"}", mapper))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jsonSchemaMatchesTheRecord() throws Exception {
        JsonNode schema;
        try (InputStream in = getClass().getResourceAsStream("/schemas/event-envelope.schema.json")) {
            schema = mapper.readTree(in);
        }
        Set<String> schemaProperties = new HashSet<>();
        schema.get("properties").fieldNames().forEachRemaining(schemaProperties::add);
        Set<String> recordComponents = Arrays.stream(EventEnvelope.class.getRecordComponents())
                .map(c -> c.getName()).collect(Collectors.toSet());
        Set<String> required = new HashSet<>();
        schema.get("required").forEach(n -> required.add(n.asText()));

        assertThat(schemaProperties).isEqualTo(recordComponents);
        assertThat(required).containsExactlyInAnyOrder("eventId", "eventType", "occurredAt", "itemType", "itemId", "payload");
    }

    @Test
    void trackPlayedPayloadSchemaMatchesTheRecord() throws Exception {
        JsonNode schema;
        try (InputStream in = getClass().getResourceAsStream("/schemas/event-envelope.schema.json")) {
            schema = mapper.readTree(in);
        }
        Set<String> schemaProperties = new HashSet<>();
        schema.at("/$defs/trackPlayedPayload/properties").fieldNames().forEachRemaining(schemaProperties::add);
        JsonNode serialized = mapper.valueToTree(new TrackPlayedPayload(UuidV7.generate(), 1, false, false, "OTHER",
                null, null, null, null, null));
        Set<String> jsonFields = new HashSet<>();
        serialized.fieldNames().forEachRemaining(jsonFields::add);

        assertThat(schemaProperties).isEqualTo(jsonFields);
    }
}
