package com.cadence.activity;

import com.cadence.IntegrationTest;
import com.cadence.activity.infrastructure.RecommenderClient;
import com.cadence.support.ApiClient.Session;
import com.cadence.support.CatalogFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The recommender path of spec 3.5 against a WireMock stub of the recommender's real contract
 * ({@code GET /v1/recommendations}, X-Api-Key; D88). The only IT with the recommender enabled, so it runs in its own
 * Spring context.
 */
class RecommendationIT extends IntegrationTest {

    static final String API_KEY = "test-recs-key";
    static final WireMockServer RECOMMENDER = new WireMockServer(options().dynamicPort());

    static {
        RECOMMENDER.start();
    }

    @DynamicPropertySource
    static void recommender(DynamicPropertyRegistry registry) {
        registry.add("cadence.recommender.enabled", () -> "true");
        registry.add("cadence.recommender.base-url", RECOMMENDER::baseUrl);
        registry.add("cadence.recommender.api-key", () -> API_KEY);
    }

    @Autowired
    JdbcClient jdbc;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    RecommenderClient client;
    @Autowired
    ObjectMapper json;

    private CatalogFixtures catalog;
    private UUID album;
    private Session user;

    @BeforeEach
    void setUp() {
        RECOMMENDER.resetAll();
        client.resetCircuit();
        catalog = new CatalogFixtures(api, api.admin().accessToken());
        album = catalog.album(catalog.artist("Recs Artist"), "Recs Album", LocalDate.of(2024, 5, 1), "Synthwave");
        user = api.register();
    }

    @AfterEach
    void closeCircuit() {
        client.resetCircuit();
    }

    /** Spec 9 Phase 3 AC2, against the stubbed recommender. */
    @Test
    void aUserWith20PlaysGetsAtLeast20RecommendationsNoneLikedAllReady() {
        List<UUID> ready = readyTracks(30);
        ready.subList(0, 22).forEach(t -> play(user, t));                 // 22 plays
        List<UUID> liked = ready.subList(22, 25);
        liked.forEach(t -> assertThat(api.put("/api/v1/me/likes/tracks/" + t, null, user.accessToken())
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT));
        UUID processing = catalog.track(album, "Still processing", 99);
        List<String> answer = new ArrayList<>();
        answer.add(processing.toString());                                 // not READY
        liked.forEach(t -> answer.add(t.toString()));                     // already liked
        answer.add(UUID.randomUUID().toString());                         // unknown to the catalog
        answer.add("s_000042");                                           // not a Cadence track id
        ready.forEach(t -> answer.add(t.toString()));
        stub(get(urlPathEqualTo("/v1/recommendations")), okJson(response("rec-ac2", answer)));

        JsonNode body = recommendations(user, "?limit=30");

        assertThat(body.get("source").asText()).isEqualTo("recommender");
        assertThat(body.get("recommendationId").asText()).isEqualTo("rec-ac2");
        List<JsonNode> items = list(body.get("items"));
        assertThat(items).hasSizeGreaterThanOrEqualTo(20).hasSize(27);   // 30 READY − 3 liked
        assertThat(items).allSatisfy(i -> {
            assertThat(i.at("/track/status").asText()).isEqualTo("READY");
            assertThat(liked).doesNotContain(UUID.fromString(i.at("/track/id").asText()));
        });
        assertThat(items.getFirst().get("reason").asText()).isEqualTo("SIMILAR_TO_RECENT");
        assertThat(items.getFirst().get("position").asInt()).as("the recommender's own slot").isEqualTo(6);
        assertThat(body.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void requestsFollowTheRecommendersContract() {
        UUID seed = readyTracks(1).getFirst();
        stub(get(urlPathEqualTo("/v1/recommendations")), okJson(response("r", List.of())));

        recommendations(user, "");
        recommendations(user, "?seedTrackId=" + seed + "&limit=5");

        RECOMMENDER.verify(getRequestedFor(urlPathEqualTo("/v1/recommendations"))
                .withQueryParam("userId", equalTo(user.userId().toString()))
                .withQueryParam("domain", equalTo("song"))
                .withQueryParam("context", equalTo("home"))
                .withQueryParam("limit", equalTo("50"))
                .withHeader("X-Api-Key", equalTo(API_KEY)));
        RECOMMENDER.verify(getRequestedFor(urlPathEqualTo("/v1/recommendations"))
                .withQueryParam("context", equalTo("radio"))
                .withQueryParam("seedItemId", equalTo(seed.toString())));
    }

    @Test
    void theRecommendersAnswerIsCachedPerUserForTenMinutes() {
        List<UUID> ready = readyTracks(3);
        stub(get(urlPathEqualTo("/v1/recommendations")), okJson(response("rec-cached", ids(ready))));

        JsonNode first = recommendations(user, "?limit=3");
        JsonNode second = recommendations(user, "?limit=2");
        Session other = api.register();
        recommendations(other, "");

        assertThat(first.get("recommendationId").asText()).isEqualTo("rec-cached");
        assertThat(second.get("items")).hasSize(2);
        assertThat(requests()).as("one call per user").isEqualTo(2);
        Long ttl = redis.getExpire("cadence:recs:" + user.userId() + ":home:-");
        assertThat(ttl).isBetween(590L, 600L);
    }

    @Test
    void aLikeAfterCachingStillRemovesTheTrack() {
        List<UUID> ready = readyTracks(3);
        stub(get(urlPathEqualTo("/v1/recommendations")), okJson(response("r", ids(ready))));
        recommendations(user, "");

        api.put("/api/v1/me/likes/tracks/" + ready.getFirst(), null, user.accessToken());

        assertThat(list(recommendations(user, "").get("items"))).extracting(i -> i.at("/track/id").asText())
                .containsExactly(ready.get(1).toString(), ready.get(2).toString());
    }

    @Test
    void aSlowRecommenderIsCutOffAt300MsAndTheFallbackIsServed() {
        readyTracks(2);
        stub(get(urlPathEqualTo("/v1/recommendations")), okJson(response("slow", List.of())).withFixedDelay(2_000));

        long started = System.nanoTime();
        ResponseEntity<JsonNode> response = api.get("/api/v1/me/recommendations", user.accessToken());
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("source").asText()).isEqualTo("fallback");
        assertThat(response.getBody().get("items")).isNotEmpty();
        assertThat(took).isLessThan(Duration.ofMillis(1_500));
    }

    /** D37: a Retry-After must never be waited out, and nothing is retried behind our back. */
    @Test
    void a503Or429WithRetryAfterIsNeitherRetriedNorWaitedOut() {
        readyTracks(1);
        for (int status : List.of(503, 429)) {
            RECOMMENDER.resetRequests();
            stub(get(urlPathEqualTo("/v1/recommendations")),
                    aResponse().withStatus(status).withHeader("Retry-After", "30"));
            Session someone = api.register();

            long started = System.nanoTime();
            JsonNode body = recommendations(someone, "");
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            assertThat(body.get("source").asText()).isEqualTo("fallback");
            assertThat(requests()).as("HTTP %d: exactly one attempt", status).isEqualTo(1);
            assertThat(took).as("HTTP %d answered without waiting", status).isLessThan(Duration.ofSeconds(2));
        }
    }

    @Test
    void theCircuitOpensAfterRepeatedFailuresAndStopsCallingTheRecommender() {
        readyTracks(1);
        stub(get(urlPathEqualTo("/v1/recommendations")), aResponse().withStatus(500));

        for (int i = 0; i < 5; i++) {
            assertThat(recommendations(api.register(), "").get("source").asText()).isEqualTo("fallback");
        }
        assertThat(client.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
        int calls = requests();
        for (int i = 0; i < 3; i++) {
            assertThat(api.get("/api/v1/home", user.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(requests()).as("no calls while open").isEqualTo(calls);
    }

    @Test
    void anEmptyOrUnusableAnswerFallsBack() {
        List<UUID> ready = readyTracks(2);
        ready.forEach(t -> api.put("/api/v1/me/likes/tracks/" + t, null, user.accessToken()));
        stub(get(urlPathEqualTo("/v1/recommendations")), okJson(response("liked-only", ids(ready))));
        UUID other = readyTracks(1).getFirst();

        JsonNode body = recommendations(user, "");

        assertThat(body.get("source").asText()).isEqualTo("fallback");
        assertThat(body.get("recommendationId").isNull()).isTrue();
        assertThat(list(body.get("items"))).extracting(i -> i.at("/track/id").asText()).contains(other.toString())
                .doesNotContain(ready.get(0).toString(), ready.get(1).toString());
    }

    @Test
    void homeHasMadeForYouAndBecauseYouListenedShelvesFromTheRecommender() {
        List<UUID> ready = readyTracks(6);
        UUID lastPlayed = ready.getFirst();
        play(user, lastPlayed);
        stub(get(urlPathEqualTo("/v1/recommendations")).withQueryParam("context", equalTo("home")),
                okJson(response("rec-home", ids(ready.subList(1, 4)))));
        stub(get(urlPathEqualTo("/v1/recommendations")).withQueryParam("context", equalTo("radio")),
                okJson(response("rec-radio", ids(List.of(lastPlayed, ready.get(4), ready.get(5))))));

        JsonNode shelves = api.get("/api/v1/home", user.accessToken()).getBody().get("shelves");

        assertThat(list(shelves)).extracting(s -> s.get("id").asText())
                .startsWith("recently-played", "made-for-you", "because-you-listened");
        JsonNode forYou = shelves.get(1);
        JsonNode similar = shelves.get(2);
        assertThat(forYou.get("title").asText()).isEqualTo("Made for you");
        assertThat(forYou.get("source").asText()).isEqualTo("recommender");
        assertThat(forYou.get("recommendationId").asText()).isEqualTo("rec-home");
        assertThat(forYou.get("items")).hasSize(3);
        assertThat(forYou.at("/items/0/position").asInt()).isZero();
        assertThat(similar.get("title").asText()).isEqualTo("Because you listened to Recs track 0");
        assertThat(similar.get("recommendationId").asText()).isEqualTo("rec-radio");
        assertThat(list(similar.get("items"))).extracting(i -> i.at("/track/id").asText())
                .as("the seed itself is never recommended").containsExactly(ready.get(4).toString(), ready.get(5).toString());
        assertThat(shelves.get(0).has("source")).isFalse();
    }

    @Test
    void validatesTheLimitAndRequiresAuthentication() {
        assertThat(api.get("/api/v1/me/recommendations?limit=0", user.accessToken()).getBody().get("code").asText())
                .isEqualTo("invalid-limit");
        assertThat(api.get("/api/v1/me/recommendations?limit=51", user.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.get("/api/v1/me/recommendations?seedTrackId=nope", user.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.get("/api/v1/me/recommendations", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------------------------------------------------

    private List<UUID> readyTracks(int n) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID track = catalog.track(album, "Recs track " + i, i + 1);
            CatalogFixtures.forceReady(jdbc, track, 180_000, 0);
            ids.add(track);
        }
        return ids;
    }

    private void play(Session who, UUID track) {
        assertThat(api.post("/api/v1/activity/plays", Map.of("playId", UUID.randomUUID(), "trackId", track,
                "msPlayed", 40_000, "source", "ALBUM", "completed", true), who.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private JsonNode recommendations(Session who, String query) {
        ResponseEntity<JsonNode> response = api.get("/api/v1/me/recommendations" + query, who.accessToken());
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /** A response in the recommender's exact shape (its RecommendationController.Response). */
    private String response(String recommendationId, List<String> itemIds) {
        ObjectNode body = json.createObjectNode()
                .put("recommendationId", recommendationId).put("userId", "u").put("domain", "song")
                .put("context", "home").put("variantId", "control").put("rankerVersion", "heuristic-v1")
                .put("indexVersion", "items_mock_512_v2").put("fallbackLevel", "NONE")
                .put("generatedAt", "2026-10-05T09:15:04.002Z");
        ArrayNode items = body.putArray("items");
        for (int i = 0; i < itemIds.size(); i++) {
            items.addObject().put("itemId", itemIds.get(i)).put("position", i).put("score", 0.9 - i / 100.0)
                    .put("recommendationId", recommendationId).put("reasonCode", "SIMILAR_TO_RECENT")
                    .putNull("explanation").put("explore", false).putObject("reasonContext");
        }
        return body.toString();
    }

    private static List<String> ids(List<UUID> tracks) {
        return tracks.stream().map(UUID::toString).toList();
    }

    private static void stub(MappingBuilder request, ResponseDefinitionBuilder response) {
        RECOMMENDER.stubFor(request.willReturn(response));
    }

    private static int requests() {
        return RECOMMENDER.findAll(getRequestedFor(urlPathEqualTo("/v1/recommendations"))).size();
    }

    private static List<JsonNode> list(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).toList();
    }
}
