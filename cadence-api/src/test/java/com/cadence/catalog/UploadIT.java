package com.cadence.catalog;

import com.cadence.IntegrationTest;
import com.cadence.common.storage.ObjectStorage;
import com.cadence.events.EventEnvelope;
import com.cadence.events.Topics;
import com.cadence.support.CatalogFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class UploadIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectStorage storage;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    ConsumerFactory<String, String> consumerFactory;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private String admin;
    private CatalogFixtures catalog;
    private UUID album;
    private Consumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

    @BeforeEach
    void setUp() {
        admin = api.admin().accessToken();
        catalog = new CatalogFixtures(api, admin);
        album = catalog.album(catalog.artist("Uploader"), "Uploads", LocalDate.of(2024, 1, 1));
        consumer = consumerFactory.createConsumer("upload-it-" + UUID.randomUUID(), null);
        consumer.subscribe(List.of(Topics.CATALOG_TRACK_UPLOADED));
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    @Test
    void realPresignedUploadThenCompleteStartsTranscoding() throws Exception {
        UUID track = catalog.track(album, "Uploaded", 1);
        byte[] mp3 = fakeMp3(4096);

        JsonNode url = uploadUrl(track, "MP3", mp3.length).getBody();
        assertThat(url.get("objectKey").asText()).isEqualTo("raw/" + track + "/source.mp3");
        assertThat(url.get("method").asText()).isEqualTo("PUT");
        assertThat(url.at("/headers/Content-Type").asText()).isEqualTo("audio/mpeg");
        assertThat(put(url, mp3)).isEqualTo(200);
        assertThat(storage.head(storage.rawBucket(), "raw/" + track + "/source.mp3")).get()
                .extracting(ObjectStorage.ObjectInfo::sizeBytes).isEqualTo((long) mp3.length);

        ResponseEntity<JsonNode> complete = api.post("/api/v1/admin/tracks/" + track + "/upload-complete", null, admin);
        assertThat(complete.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(complete.getBody().get("status").asText()).isEqualTo("PROCESSING");

        ConsumerRecord<String, String> record = awaitUploadedEvent(track);
        EventEnvelope event = EventEnvelope.fromJson(record.value(), objectMapper);
        assertThat(event.eventType()).isEqualTo("track-uploaded");
        assertThat(event.itemType()).isEqualTo("song");
        assertThat(event.payload().get("sourceKey").asText()).isEqualTo("raw/" + track + "/source.mp3");
        assertThat(event.payload().get("bucket").asText()).isEqualTo(storage.rawBucket());
        assertThat(event.payload().get("sizeBytes").asLong()).isEqualTo(mp3.length);

        // idempotent: a repeated upload-complete starts no second job
        assertThat(api.post("/api/v1/admin/tracks/" + track + "/upload-complete", null, admin).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(uploadedEventCount(track)).isEqualTo(1);
        // and no new upload while processing
        assertThat(uploadUrl(track, "mp3", 10).getBody().get("code").asText()).isEqualTo("track-processing");
    }

    @Test
    void uploadUrlValidation() {
        UUID track = catalog.track(album, "Validate", 1);

        ResponseEntity<JsonNode> exe = uploadUrl(track, "exe", 100);
        ResponseEntity<JsonNode> tooBig = uploadUrl(track, "flac", 200L * 1024 * 1024 + 1);
        ResponseEntity<JsonNode> unknownTrack = uploadUrl(UUID.randomUUID(), "wav", 100);
        ResponseEntity<JsonNode> asListener = api.post("/api/v1/admin/tracks/" + track + "/upload-url",
                Map.of("extension", "mp3", "sizeBytes", 100), api.register().accessToken());

        assertThat(exe.getBody().get("code").asText()).isEqualTo("unsupported-format");
        assertThat(tooBig.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooBig.getBody().get("code").asText()).isEqualTo("upload-too-large");
        assertThat(unknownTrack.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(asListener.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(uploadUrl(track, "m4a", 200L * 1024 * 1024).getStatusCode()).as("exactly 200 MB is allowed")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void storageRejectsUploadsOfAnotherSizeThanSigned() throws Exception {
        UUID track = catalog.track(album, "Size", 1);
        JsonNode url = uploadUrl(track, "mp3", 100).getBody();

        assertThat(put(url, fakeMp3(150))).isEqualTo(403);
    }

    @Test
    void completeWithoutUploadIsAConflict() {
        UUID neverRequested = catalog.track(album, "No URL", 1);
        UUID requestedNotUploaded = catalog.track(album, "No PUT", 2);
        uploadUrl(requestedNotUploaded, "wav", 100);

        ResponseEntity<JsonNode> first = api.post("/api/v1/admin/tracks/" + neverRequested + "/upload-complete", null, admin);
        ResponseEntity<JsonNode> second = api.post("/api/v1/admin/tracks/" + requestedNotUploaded + "/upload-complete", null, admin);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(first.getBody().get("code").asText()).isEqualTo("source-not-uploaded");
        assertThat(second.getBody().get("code").asText()).isEqualTo("source-not-uploaded");
    }

    @Test
    void nonAudioContentIsRejectedAndDeleted() throws Exception {
        UUID track = catalog.track(album, "Fake", 1);
        byte[] html = "<!DOCTYPE html><html>definitely not audio</html>".getBytes(StandardCharsets.UTF_8);
        JsonNode url = uploadUrl(track, "mp3", html.length).getBody();
        assertThat(put(url, html)).isEqualTo(200);

        ResponseEntity<JsonNode> complete = api.post("/api/v1/admin/tracks/" + track + "/upload-complete", null, admin);

        assertThat(complete.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(complete.getBody().get("code").asText()).isEqualTo("invalid-audio");
        assertThat(storage.head(storage.rawBucket(), url.get("objectKey").asText())).isEmpty();
        assertThat(api.get("/api/v1/admin/tracks/" + track, admin).getBody().get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void retranscodeOnlyForFailedTracks() throws Exception {
        UUID track = catalog.track(album, "Retry", 1);
        ResponseEntity<JsonNode> draft = api.post("/api/v1/admin/tracks/" + track + "/retranscode", null, admin);
        assertThat(draft.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(draft.getBody().get("code").asText()).isEqualTo("track-not-failed");

        byte[] mp3 = fakeMp3(1024);
        put(uploadUrl(track, "mp3", mp3.length).getBody(), mp3);
        api.post("/api/v1/admin/tracks/" + track + "/upload-complete", null, admin);
        CatalogFixtures.forceStatus(jdbc, track, "FAILED");

        ResponseEntity<JsonNode> retry = api.post("/api/v1/admin/tracks/" + track + "/retranscode", null, admin);

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(retry.getBody().get("status").asText()).isEqualTo("PROCESSING");
        assertThat(uploadedEventCount(track)).isEqualTo(2);
        assertThat(api.post("/api/v1/admin/tracks/" + UUID.randomUUID() + "/retranscode", null, admin).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<JsonNode> uploadUrl(UUID track, String extension, long size) {
        return api.post("/api/v1/admin/tracks/" + track + "/upload-url", Map.of("extension", extension, "sizeBytes", size), admin);
    }

    private int put(JsonNode url, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.get("uploadUrl").asText()))
                .header("Content-Type", url.at("/headers/Content-Type").asText())
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private ConsumerRecord<String, String> awaitUploadedEvent(UUID track) {
        return await().atMost(Duration.ofSeconds(20)).until(() -> {
            consumer.poll(Duration.ofMillis(250)).forEach(received::add);
            return received.stream().filter(r -> r.key().equals(track.toString())).findFirst().orElse(null);
        }, r -> r != null);
    }

    private long uploadedEventCount(UUID track) {
        return jdbc.sql("SELECT count(*) FROM outbox_event WHERE topic = :t AND event_key = :k")
                .param("t", Topics.CATALOG_TRACK_UPLOADED).param("k", track.toString()).query(Long.class).single();
    }

    static byte[] fakeMp3(int size) {
        byte[] data = new byte[size];
        ThreadLocalRandom.current().nextBytes(data);
        data[0] = 'I';
        data[1] = 'D';
        data[2] = '3';
        return data;
    }
}
