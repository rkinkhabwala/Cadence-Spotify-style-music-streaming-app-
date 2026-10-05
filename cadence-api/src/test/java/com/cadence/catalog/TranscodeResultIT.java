package com.cadence.catalog;

import com.cadence.IntegrationTest;
import com.cadence.common.storage.ObjectStorage;
import com.cadence.events.EventEnvelope;
import com.cadence.events.EventTypes;
import com.cadence.events.ItemTypes;
import com.cadence.events.Topics;
import com.cadence.events.TrackTranscodeFailedPayload;
import com.cadence.events.UuidV7;
import com.cadence.support.StreamingFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The catalog side of spec 3.3 step 6: transcoder results drive READY/FAILED exactly once. */
class TranscodeResultIT extends IntegrationTest {

    @Autowired
    ObjectStorage storage;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JdbcClient jdbc;

    private StreamingFixtures streaming;
    private String admin;

    @BeforeEach
    void setUp() {
        admin = api.admin().accessToken();
        streaming = new StreamingFixtures(api, admin, storage, kafka, objectMapper, jdbc);
    }

    @Test
    void transcodedEventMakesTheTrackReadyExactlyOnce() {
        UUID track = streaming.processingTrack("Exactly once");
        EventEnvelope event = streaming.sendTranscoded(track, streaming.jobIdOf(track));
        streaming.awaitStatus(track, "READY");

        streaming.send(Topics.STREAMING_TRACK_TRANSCODED, event); // duplicate delivery
        UUID marker = UuidV7.generate();
        streaming.send(Topics.STREAMING_TRACK_TRANSCODED, staleFor(track, marker)); // processed after the duplicate
        await().atMost(Duration.ofSeconds(10)).until(() -> processed(marker));

        JsonNode view = api.get("/api/v1/admin/tracks/" + track, admin).getBody();
        assertThat(view.get("durationMs").asInt()).isEqualTo(StreamingFixtures.DURATION_MS);
        assertThat(view.get("loudnessLufs").asDouble()).isEqualTo(-14.5);
        assertThat(processedCount(event.eventId())).isEqualTo(1);
        assertThat(readyChanges(track)).as("one entity-changed for the READY transition").isEqualTo(1);
    }

    @Test
    void resultsOfAStaleJobAreIgnored() {
        UUID track = streaming.processingTrack("Stale");
        UUID marker = UuidV7.generate();

        streaming.send(Topics.STREAMING_TRACK_TRANSCODED, staleFor(track, marker));
        await().atMost(Duration.ofSeconds(10)).until(() -> processed(marker));

        assertThat(api.get("/api/v1/admin/tracks/" + track, admin).getBody().get("status").asText()).isEqualTo("PROCESSING");
    }

    @Test
    void failedEventMarksTheTrackFailedAndItCanBeRetried() {
        UUID track = streaming.processingTrack("Broken");
        EventEnvelope failed = EventEnvelope.create(EventTypes.TRACK_TRANSCODE_FAILED, Instant.now(), null, ItemTypes.SONG,
                track, new TrackTranscodeFailedPayload(streaming.jobIdOf(track), "ffmpeg exited with 1"), objectMapper);

        streaming.send(Topics.STREAMING_TRACK_TRANSCODE_FAILED, failed);
        streaming.awaitStatus(track, "FAILED");

        JsonNode view = api.get("/api/v1/admin/tracks/" + track, admin).getBody();
        assertThat(view.get("failureReason").asText()).isEqualTo("ffmpeg exited with 1");
        assertThat(api.get("/api/v1/admin/tracks?status=FAILED&limit=100", admin).getBody().get("items"))
                .extracting(t -> t.get("id").asText()).contains(track.toString());
        assertThat(api.post("/api/v1/admin/tracks/" + track + "/retranscode", null, admin).getBody().get("status").asText())
                .isEqualTo("PROCESSING");
    }

    private EventEnvelope staleFor(UUID track, UUID eventId) {
        return EventEnvelope.create(eventId, EventTypes.TRACK_TRANSCODED, Instant.now(), null, ItemTypes.SONG, track,
                new com.cadence.events.TrackTranscodedPayload(UuidV7.generate(), 1, null, java.util.List.of(96), "b", "m", "f"),
                objectMapper);
    }

    private boolean processed(UUID eventId) {
        return processedCount(eventId) == 1;
    }

    private long processedCount(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM processed_event WHERE event_id = :id").param("id", eventId)
                .query(Long.class).single();
    }

    private long readyChanges(UUID track) {
        return jdbc.sql("""
                        SELECT count(*) FROM outbox_event WHERE topic = 'catalog.entity-changed' AND event_key = :k
                        AND payload -> 'payload' -> 'snapshot' ->> 'status' = 'READY'""")
                .param("k", track.toString()).query(Long.class).single();
    }
}
