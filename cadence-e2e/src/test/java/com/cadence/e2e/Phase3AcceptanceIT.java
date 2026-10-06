package com.cadence.e2e;

import com.cadence.events.CadenceJackson;
import com.cadence.events.EventEnvelope;
import com.cadence.events.recommender.RecommenderMapping;
import com.cadence.events.recommender.RecommenderMapping.UserEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Spec 9, Phase 3 acceptance criteria 1–5 against the packaged applications. The recommender is a WireMock process
 * serving its real HTTP contract (INTEGRATION.md, D88); "stopping the recommender container" stops that server. AC6
 * (Gatling) needs the running stack and is recorded in PROGRESS.md from `make loadtest`.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase3AcceptanceIT {

    static final ObjectMapper JSON = CadenceJackson.newObjectMapper();
    static final WireMockServer RECOMMENDER = new WireMockServer(options().dynamicPort());
    static CadenceStack stack;
    static Http http;
    static String adminToken;
    static String listenerToken;
    static UUID listenerId;
    static UUID album;
    static final List<UUID> tracks = new ArrayList<>();

    @BeforeAll
    static void startStack() throws Exception {
        RECOMMENDER.start();
        stack = CadenceStack.start(List.of(
                "--cadence.recommender.enabled=true",
                "--cadence.recommender.base-url=" + RECOMMENDER.baseUrl(),
                "--cadence.recommender.api-key=e2e-key"));
        http = new Http(stack.apiUrl());
        adminToken = http.post("/api/v1/auth/login", Map.of("email", CadenceStack.ADMIN_EMAIL,
                "password", CadenceStack.ADMIN_PASSWORD), null).body().get("accessToken").asText();
        listenerToken = register("phase3");
        listenerId = UUID.fromString(http.get("/api/v1/me", listenerToken).body().get("id").asText());

        UUID artist = id(http.post("/api/v1/admin/artists", Map.of("name", "Phase Three"), adminToken));
        album = id(http.post("/api/v1/admin/albums", Map.of("title", "Acceptance", "artistId", artist,
                "releaseDate", "2026-01-01", "type", "ALBUM", "genres", List.of("Synthpop")), adminToken));
        byte[] mp3 = Media.toneMp3(2, 523);
        for (int i = 0; i < 25; i++) {
            tracks.add(upload("Signal " + i, i + 1, mp3));
        }
        await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(1)).until(() -> tracks.stream().allMatch(t ->
                "READY".equals(http.get("/api/v1/admin/tracks/" + t, adminToken).body().get("status").asText())));
    }

    @AfterAll
    static void stopStack() {
        if (stack != null) {
            stack.close();
        }
        if (RECOMMENDER.isRunning()) {
            RECOMMENDER.stop();
        }
    }

    @Test
    @Order(1)
    @DisplayName("AC1: a play event in Cadence is visible to the recommender's subscription within 2 seconds")
    void playVisibleToTheRecommenderWithin2Seconds() {
        try (KafkaConsumer<String, String> recommenderSide = consumerAtEnd("activity.track-played")) {
            UUID playId = UUID.randomUUID();
            Instant sent = Instant.now();
            assertThat(http.post("/api/v1/activity/plays", Map.of("playId", playId, "trackId", tracks.getFirst(),
                    "msPlayed", 200_000, "source", "ALBUM", "completed", true, "sessionId", "e2e-tab"), listenerToken)
                    .status()).isEqualTo(200);

            EventEnvelope event = null;
            Instant received = null;
            while (event == null && Duration.between(sent, Instant.now()).compareTo(Duration.ofSeconds(10)) < 0) {
                for (ConsumerRecord<String, String> record : recommenderSide.poll(Duration.ofMillis(50))) {
                    if (record.value().contains(playId.toString())) {
                        event = EventEnvelope.fromJson(record.value(), JSON);
                        received = Instant.now();
                    }
                }
            }

            assertThat(event).as("event on activity.track-played").isNotNull();
            assertThat(Duration.between(sent, received)).isLessThan(Duration.ofSeconds(2));
            UserEvent mapped = RecommenderMapping.toUserEvent(event, JSON).orElseThrow();
            assertThat(mapped.eventType()).isEqualTo("play_end");
            assertThat(mapped.userId()).isEqualTo(listenerId.toString());
            assertThat(mapped.itemId()).isEqualTo(tracks.getFirst().toString());
            assertThat(mapped.sessionId()).isEqualTo("e2e-tab");
            assertThat(mapped.media().durationMs()).isPositive();
        }
    }

    @Test
    @Order(2)
    @DisplayName("AC2: a user with 20+ plays gets at least 20 recommendations from the recommender, none liked, all READY")
    void twentyRecommendationsNoneLikedAllReady() {
        for (UUID track : tracks.subList(0, 22)) {
            assertThat(http.post("/api/v1/activity/plays", Map.of("playId", UUID.randomUUID(), "trackId", track,
                    "msPlayed", 40_000, "source", "ALBUM", "completed", true), listenerToken).status()).isEqualTo(200);
        }
        List<UUID> liked = tracks.subList(22, 25);
        liked.forEach(t -> assertThat(http.put("/api/v1/me/likes/tracks/" + t, null, listenerToken).status()).isEqualTo(204));
        UUID draft = id(http.post("/api/v1/admin/tracks", Map.of("title", "Never uploaded", "albumId", album,
                "trackNumber", 99), adminToken));
        List<String> answer = new ArrayList<>(List.of(draft.toString()));
        tracks.forEach(t -> answer.add(t.toString()));
        RECOMMENDER.stubFor(get(urlPathEqualTo("/v1/recommendations")).willReturn(okJson(recommenderResponse("e2e-rec", answer))));

        JsonNode body = http.get("/api/v1/me/recommendations?limit=30", listenerToken).body();

        assertThat(body.get("source").asText()).isEqualTo("recommender");
        assertThat(body.get("items").size()).isGreaterThanOrEqualTo(20);
        Set<String> likedIds = Set.of(liked.get(0).toString(), liked.get(1).toString(), liked.get(2).toString());
        body.get("items").forEach(i -> {
            assertThat(i.at("/track/status").asText()).isEqualTo("READY");
            assertThat(likedIds).doesNotContain(i.at("/track/id").asText());
        });
        RECOMMENDER.verify(getRequestedFor(urlPathEqualTo("/v1/recommendations")));
    }

    @Test
    @Order(3)
    @DisplayName("AC3: stopping the recommender still returns recommendations (fallback) with no 5xx from Cadence")
    void recommenderDownMeansFallbackNotErrors() {
        RECOMMENDER.stop();
        for (int i = 0; i < 8; i++) {   // enough failures to open the circuit too
            String token = register("ac3-" + i);
            Http.Response recs = http.get("/api/v1/me/recommendations", token);
            Http.Response home = http.get("/api/v1/home", token);

            assertThat(recs.status()).isEqualTo(200);
            assertThat(recs.body().get("source").asText()).isEqualTo("fallback");
            assertThat(recs.body().get("items").size()).isPositive();
            assertThat(home.status()).isEqualTo(200);
            assertThat(home.body().at("/shelves/0/id").asText()).isEqualTo("made-for-you");
        }
        Http.Response cached = http.get("/api/v1/me/recommendations", listenerToken);
        assertThat(cached.status()).as("the earlier answer is still cached for 10 minutes").isEqualTo(200);
    }

    @Test
    @Order(4)
    @DisplayName("AC4: a free user cannot obtain the 320 kbps rendition; a 7th skip within an hour returns 429")
    void freeUserBitrateCapAndSkipLimit() {
        String free = register("free");
        Http.Response start = http.post("/api/v1/playback/" + tracks.getFirst(), null, free);
        assertThat(start.status()).isEqualTo(200);
        String master = http.get(start.body().get("manifestUrl").asText(), null).raw().body();
        assertThat(master).contains("96k/index.m3u8", "160k/index.m3u8").doesNotContain("320k");
        String token = master.lines().filter(l -> l.startsWith("160k/")).findFirst().orElseThrow().split("token=")[1];
        Http.Response premiumRendition = http.get("/api/v1/playback/" + tracks.getFirst() + "/320k/index.m3u8?token=" + token, null);
        assertThat(premiumRendition.status()).isEqualTo(403);
        assertThat(premiumRendition.body().get("code").asText()).isEqualTo("bitrate-not-allowed");

        for (int i = 1; i <= 6; i++) {
            assertThat(http.post("/api/v1/playback/" + tracks.get(i) + "/skip", Map.of("playId", UUID.randomUUID()), free)
                    .status()).as("skip %d", i).isEqualTo(200);
        }
        Http.Response seventh = http.post("/api/v1/playback/" + tracks.get(7) + "/skip", Map.of("playId", UUID.randomUUID()), free);
        assertThat(seventh.status()).isEqualTo(429);
        assertThat(seventh.body().get("code").asText()).isEqualTo("skip-limit-reached");
        assertThat(seventh.header("Retry-After")).isNotBlank();
    }

    @Test
    @Order(5)
    @DisplayName("AC5: two collaborators editing the same playlist concurrently do not lose writes")
    void collaboratorsDoNotLoseWrites() throws Exception {
        String owner = register("owner");
        String collaborator = register("collab");
        UUID playlist = id(http.post("/api/v1/playlists", Map.of("name", "Together", "collaborative", true), owner));
        String invite = http.post("/api/v1/playlists/" + playlist + "/invite", null, owner).body().get("inviteToken").asText();
        assertThat(http.post("/api/v1/playlists/" + playlist + "/collaborators", Map.of("inviteToken", invite), collaborator)
                .status()).isEqualTo(200);

        List<Callable<Integer>> edits = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String who = i % 2 == 0 ? owner : collaborator;
            UUID track = tracks.get(i);
            edits.add(() -> http.post("/api/v1/playlists/" + playlist + "/tracks", Map.of("trackIds", List.of(track)), who).status());
        }
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            for (Future<Integer> f : pool.invokeAll(edits)) {
                statuses.add(f.get());
            }
        }

        assertThat(statuses).containsOnly(200);
        JsonNode body = http.get("/api/v1/playlists/" + playlist + "?limit=100", owner).body();
        assertThat(body.get("trackCount").asInt()).isEqualTo(20);
        assertThat(body.at("/tracks/items").size()).isEqualTo(20);
    }

    // ---- helpers

    private static String register(String prefix) {
        return http.post("/api/v1/auth/register", Map.of("email", prefix + "-" + UUID.randomUUID() + "@e2e.test",
                "password", "listener-password", "displayName", "Phase Three"), null).body().get("accessToken").asText();
    }

    private static UUID upload(String title, int number, byte[] mp3) {
        UUID track = id(http.post("/api/v1/admin/tracks", Map.of("title", title, "albumId", album, "trackNumber", number), adminToken));
        JsonNode url = http.post("/api/v1/admin/tracks/" + track + "/upload-url",
                Map.of("extension", "mp3", "sizeBytes", mp3.length), adminToken).body();
        try {
            assertThat(http.putBytes(url.get("uploadUrl").asText(), mp3, "audio/mpeg").statusCode()).isEqualTo(200);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(http.post("/api/v1/admin/tracks/" + track + "/upload-complete", null, adminToken).status()).isEqualTo(202);
        return track;
    }

    private static KafkaConsumer<String, String> consumerAtEnd(String topic) {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, stack.kafka.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
        List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                .map(p -> new TopicPartition(topic, p.partition())).toList();
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        partitions.forEach(consumer::position);
        return consumer;
    }

    /** The recommender's response shape (its RecommendationController.Response). */
    private static String recommenderResponse(String recommendationId, List<String> itemIds) {
        ObjectNode body = JSON.createObjectNode().put("recommendationId", recommendationId).put("domain", "song")
                .put("context", "home").put("variantId", "control").put("fallbackLevel", "NONE");
        ArrayNode items = body.putArray("items");
        for (int i = 0; i < itemIds.size(); i++) {
            items.addObject().put("itemId", itemIds.get(i)).put("position", i).put("score", 1.0 - i / 100.0)
                    .put("reasonCode", "SIMILAR_TO_RECENT");
        }
        return body.toString();
    }

    private static UUID id(Http.Response response) {
        assertThat(response.status()).as("%s", response.body()).isIn(200, 201);
        return UUID.fromString(response.body().get("id").asText());
    }
}
