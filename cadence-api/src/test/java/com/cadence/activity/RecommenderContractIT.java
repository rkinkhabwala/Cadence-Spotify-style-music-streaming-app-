package com.cadence.activity;

import com.cadence.IntegrationTest;
import com.cadence.activity.application.TrackStatsService;
import com.cadence.events.EventTypes;
import com.cadence.events.ItemTypes;
import com.cadence.events.Topics;
import com.cadence.events.recommender.RecommenderMapping;
import com.cadence.events.recommender.RecommenderMapping.CatalogChange;
import com.cadence.events.recommender.RecommenderMapping.UserEvent;
import com.cadence.support.ApiClient.Session;
import com.cadence.support.CatalogFixtures;
import com.cadence.support.KafkaProbe;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cadence's side of the recommender contract with the default configuration (recommender disabled, D88-D90): what
 * Cadence publishes for the recommender, and the fallback that serves recommendations meanwhile.
 */
class RecommenderContractIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper json;
    @Autowired
    TrackStatsService trackStats;
    @Autowired
    KafkaConnectionDetails kafkaConnection;
    private String bootstrapServers;

    private CatalogFixtures catalog;
    private Session user;

    @BeforeEach
    void setUp() {
        catalog = new CatalogFixtures(api, api.admin().accessToken());
        user = api.register();
        bootstrapServers = String.join(",", kafkaConnection.getBootstrapServers());
    }

    /** Spec 9 Phase 3 AC1, Cadence's half: the recommender's subscription sees a play within 2 seconds. */
    @Test
    void playsLikesAndFollowsReachKafkaWithin2sInTheAlignedEnvelope() {
        UUID artist = catalog.artist("Feed Artist");
        UUID track = readyTrack(catalog.album(artist, "Feed Album", LocalDate.of(2023, 1, 1), "Jazz"), "Feed track", 210_000);
        UUID playId = UUID.randomUUID();
        try (KafkaProbe probe = new KafkaProbe(bootstrapServers, Topics.ACTIVITY_TRACK_PLAYED,
                Topics.LIBRARY_TRACK_LIKED, Topics.LIBRARY_ARTIST_FOLLOWED)) {
            Instant sent = Instant.now();
            ResponseEntity<JsonNode> report = api.post("/api/v1/activity/plays", Map.of("playId", playId,
                    "trackId", track, "msPlayed", 12_500, "source", "LIBRARY", "skipped", true,
                    "sessionId", "tab-7f3a", "recommendationId", "rec-123", "position", 4), user.accessToken());
            assertThat(report.getStatusCode()).isEqualTo(HttpStatus.OK);
            KafkaProbe.Received played = probe.await(e -> e.eventType().equals(EventTypes.TRACK_PLAYED)
                    && e.userId().equals(user.userId()), Duration.ofSeconds(5));

            assertThat(Duration.between(sent, played.arrivedAt())).isLessThan(Duration.ofSeconds(2));
            assertThat(played.key()).isEqualTo(user.userId().toString());
            UserEvent mapped = RecommenderMapping.toUserEvent(played.event(), json).orElseThrow();
            assertThat(mapped.eventType()).isEqualTo("skip");
            assertThat(mapped.itemId()).isEqualTo(track.toString());
            assertThat(mapped.value()).isEqualTo(12.5);
            assertThat(mapped.media().positionMs()).isEqualTo(12_500);
            assertThat(mapped.media().durationMs()).isEqualTo(210_000);
            assertThat(mapped.sessionId()).isEqualTo("tab-7f3a");
            assertThat(mapped.recommendationId()).isEqualTo("rec-123");
            assertThat(mapped.position()).isEqualTo(4);

            api.put("/api/v1/me/likes/tracks/" + track, null, user.accessToken());
            api.put("/api/v1/me/following/artists/" + artist, null, user.accessToken());
            UserEvent like = RecommenderMapping.toUserEvent(probe.await(e -> e.eventType().equals(EventTypes.TRACK_LIKED)
                    && e.userId().equals(user.userId()), Duration.ofSeconds(5)).event(), json).orElseThrow();
            UserEvent follow = RecommenderMapping.toUserEvent(probe.await(e -> e.eventType().equals(EventTypes.ARTIST_FOLLOWED)
                    && e.userId().equals(user.userId()), Duration.ofSeconds(5)).event(), json).orElseThrow();
            assertThat(like.eventType()).isEqualTo("like");
            assertThat(follow.eventType()).isEqualTo("follow");
            assertThat(follow.itemId()).isEqualTo(artist.toString());
        }
    }

    @Test
    void trackEventsAreSelfContainedCatalogItemsAndAlbumChangesReEmitTheirTracks() {
        UUID artist = catalog.artist("Snapshot Artist");
        UUID album = catalog.album(artist, "Snapshot Album", LocalDate.of(2021, 3, 4), "Ambient");
        UUID track = readyTrack(album, "Snapshot track", 95_000);
        try (KafkaProbe probe = new KafkaProbe(bootstrapServers, Topics.CATALOG_ENTITY_CHANGED)) {
            api.patch("/api/v1/admin/albums/" + album, Map.of("genres", List.of("Ambient", "Drone")),
                    api.admin().accessToken());

            // the track's earlier CREATED (DRAFT) event may still be on its way; the re-emitted one is READY
            KafkaProbe.Received event = probe.await(e -> e.itemType().equals(ItemTypes.SONG) && e.itemId().equals(track)
                    && "READY".equals(e.payload().at("/snapshot/status").asText()), Duration.ofSeconds(5));
            CatalogChange change = RecommenderMapping.toCatalogChange(event.event(), json).orElseThrow();

            assertThat(change.isDelete()).isFalse();
            assertThat(change.item().genres()).containsExactly("ambient", "drone");
            assertThat(change.item().releaseDate()).isEqualTo(LocalDate.of(2021, 3, 4));
            assertThat(change.item().artistId()).isEqualTo(artist.toString());
            assertThat(change.item().artistName()).isEqualTo("Snapshot Artist");
            assertThat(change.item().durationMs()).isEqualTo(95_000);
        }
    }

    @Test
    void withTheRecommenderOffRecommendationsComeFromPopularTracksInTheUsersTopGenres() {
        UUID album = catalog.album(catalog.artist("Fallback Artist"), "Fallback Album", LocalDate.of(2022, 2, 2), "Fallbackcore");
        UUID niche = readyTrack(album, "Niche favourite", 120_000);
        UUID nicheLiked = readyTrack(album, "Niche liked", 120_000);
        UUID nicheOther = readyTrack(album, "Niche other", 120_000);
        // the user's taste: Fallbackcore; global popularity: other genres
        Session listener = api.register();
        for (UUID t : List.of(niche, nicheLiked)) {
            api.post("/api/v1/activity/plays", Map.of("playId", UUID.randomUUID(), "trackId", t, "msPlayed", 40_000,
                    "source", "ALBUM", "completed", true), listener.accessToken());
        }
        api.put("/api/v1/me/likes/tracks/" + nicheLiked, null, listener.accessToken());
        trackStats.refresh();

        ResponseEntity<JsonNode> response = api.get("/api/v1/me/recommendations?limit=50", listener.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("source").asText()).isEqualTo("fallback");
        assertThat(body.get("recommendationId").isNull()).isTrue();
        List<JsonNode> items = StreamSupport.stream(body.get("items").spliterator(), false).toList();
        Map<String, String> reasons = new HashMap<>();
        items.forEach(i -> reasons.put(i.at("/track/id").asText(), i.get("reason").asText()));
        assertThat(reasons).containsEntry(niche.toString(), "POPULAR_IN_YOUR_GENRES")
                .containsEntry(nicheOther.toString(), "POPULAR_IN_YOUR_GENRES")
                .doesNotContainKey(nicheLiked.toString());
        assertThat(items.subList(0, 2)).as("the user's genres come first").extracting(i -> i.get("reason").asText())
                .containsOnly("POPULAR_IN_YOUR_GENRES");
        assertThat(items).allSatisfy(i -> assertThat(i.at("/track/status").asText()).isEqualTo("READY"));
        assertThat(api.get("/api/v1/home", listener.accessToken()).getBody().at("/shelves/1/id").asText())
                .isEqualTo("made-for-you");
    }

    @Test
    void playReportsValidateTheRecommendationContext() {
        UUID track = readyTrack(catalog.album(catalog.artist("V"), "V", LocalDate.of(2020, 1, 1)), "V", 60_000);
        for (Map<String, Object> bad : List.of(Map.<String, Object>of("sessionId", "has spaces"),
                Map.<String, Object>of("recommendationId", "x".repeat(65)), Map.<String, Object>of("position", -1))) {
            Map<String, Object> body = new HashMap<>(Map.of("trackId", track, "msPlayed", 1_000, "source", "OTHER"));
            body.putAll(bad);
            ResponseEntity<JsonNode> response = api.post("/api/v1/activity/plays", body, user.accessToken());
            assertThat(response.getStatusCode()).as("%s", bad).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("code").asText()).isEqualTo("validation-failed");
        }
    }

    private UUID readyTrack(UUID album, String title, int durationMs) {
        UUID track = catalog.track(album, title, 1);
        CatalogFixtures.forceReady(jdbc, track, durationMs, 0);
        return track;
    }
}
