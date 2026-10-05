package com.cadence.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Spec 9, Phase 2 acceptance criteria 1–4 against the real packaged applications (AC5, playback across page
 * navigation, is a web-client criterion: see cadence-web's tests and scripts/verify-web.sh). Methods share the
 * READY tracks uploaded through the real transcoder.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase2AcceptanceIT {

    static CadenceStack stack;
    static Http http;
    static String adminToken;
    static String listenerToken;
    static UUID listenerId;
    static UUID album;
    static final List<UUID> tracks = new ArrayList<>();

    @BeforeAll
    static void startStack() throws Exception {
        stack = CadenceStack.start();
        http = new Http(stack.apiUrl());
        adminToken = http.post("/api/v1/auth/login", Map.of("email", CadenceStack.ADMIN_EMAIL,
                "password", CadenceStack.ADMIN_PASSWORD), null).body().get("accessToken").asText();
        String email = "phase2-" + UUID.randomUUID() + "@e2e.test";
        listenerToken = http.post("/api/v1/auth/register", Map.of("email", email, "password", "listener-password",
                "displayName", "Phase Two"), null).body().get("accessToken").asText();
        listenerId = UUID.fromString(http.get("/api/v1/me", listenerToken).body().get("id").asText());
    }

    @AfterAll
    static void stopStack() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("AC1: typing \"beatls\" returns the correct artist in suggestions (fuzzy match)")
    void fuzzySuggestion() {
        UUID beatlz = id(http.post("/api/v1/admin/artists", Map.of("name", "The Beatlz"), adminToken));
        id(http.post("/api/v1/admin/artists", Map.of("name", "The Beatles Tribute Orchestra Of Nowhere"), adminToken));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Http.Response suggest = http.get("/api/v1/search/suggest?q=beatls", listenerToken);
            assertThat(suggest.status()).isEqualTo(200);
            assertThat(suggest.body().get("items")).anySatisfy(s -> {
                assertThat(s.get("type").asText()).isEqualTo("artist");
                assertThat(s.get("id").asText()).isEqualTo(beatlz.toString());
                assertThat(s.get("text").asText()).isEqualTo("The Beatlz");
            });
        });
    }

    @Test
    @Order(2)
    @DisplayName("AC2: a catalog edit is reflected in search within 5 seconds")
    void editReflectedInSearch() {
        UUID artist = id(http.post("/api/v1/admin/artists", Map.of("name", "Velvet Static"), adminToken));
        album = id(http.post("/api/v1/admin/albums", Map.of("title", "Late Trains", "artistId", artist,
                "releaseDate", "2020-10-09", "type", "ALBUM"), adminToken));
        await().atMost(Duration.ofSeconds(5)).until(() -> albumIds("late trains").contains(album.toString()));

        Instant edited = Instant.now();
        assertThat(http.send("PATCH", "/api/v1/admin/albums/" + album, Map.of("title", "Night Ferries"), adminToken).status())
                .isEqualTo(200);
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100)).until(() ->
                albumIds("night ferries").contains(album.toString()) && !albumIds("late trains").contains(album.toString()));
        System.out.printf("[acceptance] album rename searchable after %d ms%n", Duration.between(edited, Instant.now()).toMillis());
    }

    @Test
    @Order(3)
    @DisplayName("AC3: playing a track for 30 s increments its play count exactly once, even if the event is delivered twice")
    void playCountedExactlyOnce() throws Exception {
        byte[] mp3 = Media.toneMp3(2, 440);
        for (int i = 0; i < 51; i++) {
            tracks.add(upload("Ferry " + i, i + 1, mp3));
        }
        await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(1)).until(() -> tracks.stream().allMatch(t ->
                "READY".equals(http.get("/api/v1/admin/tracks/" + t, adminToken).body().get("status").asText())));

        UUID track = tracks.getFirst();
        UUID playId = UUID.randomUUID();
        Map<String, Object> report = Map.of("playId", playId, "trackId", track, "msPlayed", 30_000, "source", "ALBUM",
                "sourceId", album);
        assertThat(http.post("/api/v1/activity/plays", report, listenerToken).body().get("counted").asBoolean()).isTrue();
        assertThat(http.post("/api/v1/activity/plays", report, listenerToken).status()).as("client retry").isEqualTo(200);

        // the broker delivers the event a second time
        String event = trackPlayedEvent(playId);
        try (KafkaProducer<String, String> producer = producer()) {
            producer.send(new ProducerRecord<>("activity.track-played", listenerId.toString(), event)).get();
        }

        await().atMost(Duration.ofSeconds(10)).until(() -> playCount(track) == 1);
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4)).until(() -> playCount(track) == 1);
    }

    @Test
    @Order(4)
    @DisplayName("AC4: recently played shows the last 50 distinct tracks in order")
    void recentlyPlayed() {
        for (UUID track : tracks) {      // 51 tracks; the first was already played in AC3
            http.post("/api/v1/activity/plays", Map.of("playId", UUID.randomUUID(), "trackId", track, "msPlayed", 31_000,
                    "source", "ALBUM", "completed", true), listenerToken);
        }
        http.post("/api/v1/activity/plays", Map.of("playId", UUID.randomUUID(), "trackId", tracks.get(10),
                "msPlayed", 31_000, "source", "SEARCH"), listenerToken);

        JsonNode recent = http.get("/api/v1/me/recently-played", listenerToken).body();

        List<String> expected = new ArrayList<>(List.of(tracks.get(10).toString()));
        for (int i = tracks.size() - 1; expected.size() < 50; i--) {
            if (i != 10) {
                expected.add(tracks.get(i).toString());
            }
        }
        List<String> actual = new ArrayList<>();
        recent.get("items").forEach(i -> actual.add(i.at("/track/id").asText()));
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    // ---- helpers

    private UUID upload(String title, int number, byte[] mp3) throws Exception {
        UUID track = id(http.post("/api/v1/admin/tracks", Map.of("title", title, "albumId", album, "trackNumber", number), adminToken));
        JsonNode url = http.post("/api/v1/admin/tracks/" + track + "/upload-url",
                Map.of("extension", "mp3", "sizeBytes", mp3.length), adminToken).body();
        assertThat(http.putBytes(url.get("uploadUrl").asText(), mp3, "audio/mpeg").statusCode()).isEqualTo(200);
        assertThat(http.post("/api/v1/admin/tracks/" + track + "/upload-complete", null, adminToken).status()).isEqualTo(202);
        return track;
    }

    private List<String> albumIds(String q) {
        Http.Response response = http.get("/api/v1/search?types=album&q=" + q.replace(" ", "%20"), listenerToken);
        assertThat(response.status()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        response.body().at("/albums/items").forEach(a -> ids.add(a.get("id").asText()));
        return ids;
    }

    private long playCount(UUID track) {
        return http.get("/api/v1/tracks/" + track, null).body().get("playCount").asLong();
    }

    /** The event the API published for this playback, read back from Kafka. */
    private static String trackPlayedEvent(UUID playId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, stack.kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "e2e-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("activity.track-played"));
            List<String> found = new ArrayList<>();
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                    if (record.value().contains(playId.toString())) {
                        found.add(record.value());
                    }
                }
                return !found.isEmpty();
            });
            return found.getFirst();
        }
    }

    private static KafkaProducer<String, String> producer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, stack.kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return new KafkaProducer<>(props);
    }

    private static UUID id(Http.Response response) {
        assertThat(response.status()).as("%s", response.body()).isIn(200, 201);
        return UUID.fromString(response.body().get("id").asText());
    }
}
