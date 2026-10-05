package com.cadence.activity;

import com.cadence.IntegrationTest;
import com.cadence.activity.application.TrackStatsService;
import com.cadence.support.ApiClient.Session;
import com.cadence.support.CatalogFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ActivityIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    TrackStatsService trackStats;

    private CatalogFixtures catalog;
    private UUID album;
    private Session user;

    @BeforeEach
    void setUp() {
        catalog = new CatalogFixtures(api, api.admin().accessToken());
        // released today: the newest album, so it leads "New releases" whatever other tests created
        album = catalog.album(catalog.artist("Activity Artist"), "Activity Album", LocalDate.now());
        user = api.register();
    }

    /** Spec 9 Phase 2 AC3. */
    @Test
    void aThirtySecondPlayIncrementsThePlayCountExactlyOnceEvenWhenDeliveredTwice() throws Exception {
        UUID track = ready("Counted");
        UUID playId = UUID.randomUUID();

        ResponseEntity<JsonNode> first = play(user, playId, track, 30_000, false, false);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().get("counted").asBoolean()).isTrue();
        assertThat(first.getBody().get("playId").asText()).isEqualTo(playId.toString());
        // the client retries the same report, then reports completion of the same playback
        assertThat(play(user, playId, track, 30_000, false, false).getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode done = play(user, playId, track, 41_000, true, false).getBody();
        assertThat(done.get("msPlayed").asInt()).isEqualTo(41_000);
        assertThat(done.get("completed").asBoolean()).isTrue();

        List<String> published = outbox(user.userId());
        assertThat(published).as("identical retry publishes nothing").hasSize(2);
        // Kafka redelivers both events once more
        for (String event : published) {
            kafka.send("activity.track-played", user.userId().toString(), event).get();
        }

        await().atMost(Duration.ofSeconds(10)).until(() -> playCount(track) == 1);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3)).until(() -> playCount(track) == 1);
        assertThat(processedPlays(playId)).isEqualTo(1);
    }

    @Test
    void playsShorterThanThirtySecondsDoNotCount() {
        UUID track = ready("Skipped early");
        UUID barrier = ready("Barrier");

        JsonNode skip = play(user, UUID.randomUUID(), track, 29_999, false, true).getBody();
        assertThat(skip.get("counted").asBoolean()).isFalse();
        assertThat(skip.get("skipped").asBoolean()).isTrue();
        play(user, UUID.randomUUID(), barrier, 30_000, false, false);

        // events are consumed in order per user: once the later play counted, the earlier one was processed too
        await().atMost(Duration.ofSeconds(10)).until(() -> playCount(barrier) == 1);
        assertThat(playCount(track)).isZero();
    }

    @Test
    void reportingPublishesTheTrackPlayedEnvelopeKeyedByUser() {
        UUID track = ready("Published");
        UUID playlist = UUID.randomUUID();
        UUID playId = UUID.randomUUID();
        api.post("/api/v1/activity/plays", Map.of("playId", playId, "trackId", track, "msPlayed", 182_000,
                "source", "PLAYLIST", "sourceId", playlist, "completed", true), user.accessToken());

        JsonNode event = jdbc.sql("SELECT payload::text FROM outbox_event WHERE topic = 'activity.track-played' AND event_key = :k")
                .param("k", user.userId().toString()).query((rs, i) -> {
                    try {
                        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(rs.getString(1));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).single();
        assertThat(event.get("eventType").asText()).isEqualTo("track-played");
        assertThat(event.get("userId").asText()).isEqualTo(user.userId().toString());
        assertThat(event.get("itemType").asText()).isEqualTo("song");
        assertThat(event.get("itemId").asText()).isEqualTo(track.toString());
        assertThat(event.at("/payload/playId").asText()).isEqualTo(playId.toString());
        assertThat(event.at("/payload/msPlayed").asLong()).isEqualTo(182_000);
        assertThat(event.at("/payload/completed").asBoolean()).isTrue();
        assertThat(event.at("/payload/skipped").asBoolean()).isFalse();
        assertThat(event.at("/payload/source").asText()).isEqualTo("PLAYLIST");
        assertThat(event.at("/payload/sourceId").asText()).isEqualTo(playlist.toString());
    }

    /** Spec 9 Phase 2 AC4. */
    @Test
    void recentlyPlayedShowsTheLast50DistinctTracksInOrder() {
        List<UUID> tracks = new ArrayList<>();
        for (int i = 0; i < 52; i++) {
            tracks.add(ready("Recent " + i));
        }
        for (UUID track : tracks) {
            play(user, UUID.randomUUID(), track, 31_000, false, false);
        }
        play(user, UUID.randomUUID(), tracks.get(5), 31_000, false, false);   // played again: moves to the top

        JsonNode recent = api.get("/api/v1/me/recently-played", user.accessToken()).getBody();

        List<String> expected = new ArrayList<>();
        expected.add(tracks.get(5).toString());
        for (int i = 51; expected.size() < 50; i--) {
            if (i != 5) {
                expected.add(tracks.get(i).toString());
            }
        }
        assertThat(recent.get("items")).extracting(i -> i.at("/track/id").asText()).containsExactlyElementsOf(expected);
        assertThat(recent.get("nextCursor").isNull()).isTrue();
        assertThat(recent.at("/items/0/playable").asBoolean()).isTrue();
        assertThat(api.get("/api/v1/me/recently-played?limit=3", user.accessToken()).getBody().get("items"))
                .extracting(i -> i.at("/track/id").asText()).containsExactlyElementsOf(expected.subList(0, 3));
        // other users have their own history
        assertThat(api.get("/api/v1/me/recently-played", api.register().accessToken()).getBody().get("items")).isEmpty();
    }

    @Test
    void topTracksRankCountedPlaysWithinTheRange() {
        UUID a = ready("Top A"), b = ready("Top B"), c = ready("Top C"), old = ready("Top Old");
        for (int i = 0; i < 3; i++) {
            play(user, UUID.randomUUID(), a, 30_000, false, false);
        }
        play(user, UUID.randomUUID(), b, 45_000, true, false);
        play(user, UUID.randomUUID(), c, 5_000, false, true);       // not a stream
        UUID oldPlay = UUID.randomUUID();
        play(user, oldPlay, old, 60_000, true, false);
        play(user, UUID.randomUUID(), old, 60_000, true, false);
        jdbc.sql("UPDATE play_events SET started_at = now() - interval '60 days', counted_at = now() - interval '60 days' WHERE user_id = :u AND track_id = :t")
                .param("u", user.userId()).param("t", old).update();

        JsonNode shortTerm = api.get("/api/v1/me/top/tracks?range=short", user.accessToken()).getBody();
        assertThat(shortTerm.get("items")).extracting(i -> i.at("/track/id").asText() + "x" + i.get("plays").asInt())
                .containsExactly(a + "x3", b + "x1");
        JsonNode medium = api.get("/api/v1/me/top/tracks", user.accessToken()).getBody();
        assertThat(medium.get("items")).extracting(i -> i.at("/track/id").asText())
                .containsExactly(a.toString(), old.toString(), b.toString());

        JsonNode page1 = api.get("/api/v1/me/top/tracks?range=long&limit=2", user.accessToken()).getBody();
        JsonNode page2 = api.get("/api/v1/me/top/tracks?range=long&limit=2&cursor=" + page1.get("nextCursor").asText(),
                user.accessToken()).getBody();
        assertThat(page1.get("items")).hasSize(2);
        assertThat(page2.get("items")).extracting(i -> i.at("/track/id").asText()).containsExactly(b.toString());
        assertThat(page2.get("nextCursor").isNull()).isTrue();

        assertProblem(api.get("/api/v1/me/top/tracks?range=decade", user.accessToken()), HttpStatus.BAD_REQUEST, "invalid-range");
        assertThat(api.get("/api/v1/me/top/tracks", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void homeHasRecentTopPopularAndNewReleaseShelves() {
        UUID hit = ready("Home Hit");
        UUID other = ready("Home Other");
        UUID futureAlbum = catalog.album(catalog.artist("Future Artist"), "Not Out Yet", LocalDate.now().plusYears(1));
        UUID unreleased = catalog.track(futureAlbum, "Future Track", 1);
        CatalogFixtures.forceReady(jdbc, unreleased, 60_000, 0);
        Session fan = api.register();
        // more streams than any other test produces for one track, so both lead "Popular right now"
        play(user, UUID.randomUUID(), hit, 30_000, false, false);
        for (int i = 0; i < 7; i++) {
            play(fan, UUID.randomUUID(), hit, 30_000, false, false);
            if (i < 6) {
                play(fan, UUID.randomUUID(), other, 31_000, false, false);
            }
        }
        trackStats.refresh();

        JsonNode home = api.get("/api/v1/home", user.accessToken()).getBody();
        Map<String, JsonNode> shelves = new HashMap<>();
        home.get("shelves").forEach(s -> shelves.put(s.get("id").asText(), s));

        assertThat(home.get("shelves")).extracting(s -> s.get("id").asText())
                .containsExactly("recently-played", "top-tracks", "popular", "new-releases");
        assertThat(shelves.get("recently-played").at("/items/0/type").asText()).isEqualTo("track");
        assertThat(shelves.get("recently-played").at("/items/0/track/id").asText()).isEqualTo(hit.toString());
        assertThat(shelves.get("top-tracks").at("/items/0/track/id").asText()).isEqualTo(hit.toString());
        assertThat(shelves.get("popular").get("items")).extracting(i -> i.at("/track/id").asText())
                .startsWith(hit.toString(), other.toString());
        assertThat(shelves.get("new-releases").get("items")).extracting(i -> i.at("/album/id").asText())
                .contains(album.toString()).doesNotContain(futureAlbum.toString());
        assertThat(shelves.get("new-releases").at("/items/0/type").asText()).isEqualTo("album");

        JsonNode newcomer = api.get("/api/v1/home", api.register().accessToken()).getBody();
        assertThat(newcomer.get("shelves")).extracting(s -> s.get("id").asText()).containsExactly("popular", "new-releases");
        assertThat(api.get("/api/v1/home", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void trackStatsCountPlaysAndUniqueListenersOverThirtyDays() {
        UUID track = ready("Stats");
        Session other = api.register();
        play(user, UUID.randomUUID(), track, 30_000, false, false);
        play(user, UUID.randomUUID(), track, 30_000, false, false);
        play(other, UUID.randomUUID(), track, 30_000, false, false);
        play(other, UUID.randomUUID(), track, 10_000, false, true);

        trackStats.refresh();

        Map<String, Object> row = jdbc.sql("SELECT plays_30d, unique_listeners_30d FROM track_stats WHERE track_id = :t")
                .param("t", track).query().singleRow();
        assertThat(row).containsEntry("plays_30d", 3L).containsEntry("unique_listeners_30d", 2L);

        jdbc.sql("UPDATE play_events SET counted_at = now() - interval '31 days' WHERE track_id = :t").param("t", track).update();
        trackStats.refresh();
        assertThat(jdbc.sql("SELECT count(*) FROM track_stats WHERE track_id = :t").param("t", track).query(Long.class).single()).isZero();
    }

    @Test
    void invalidReportsAreRejected() {
        UUID track = ready("Validated");
        UUID draft = catalog.track(album, "Draft", 99);
        String token = user.accessToken();

        assertProblem(api.post("/api/v1/activity/plays", Map.of("trackId", track, "msPlayed", -1, "source", "ALBUM"), token),
                HttpStatus.BAD_REQUEST, "validation-failed");
        assertProblem(api.post("/api/v1/activity/plays", Map.of("trackId", track, "msPlayed", 1000), token),
                HttpStatus.BAD_REQUEST, "validation-failed");
        assertThat(api.post("/api/v1/activity/plays", Map.of("trackId", track, "msPlayed", 1000, "source", "TV"), token)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertProblem(api.post("/api/v1/activity/plays", Map.of("trackId", UUID.randomUUID(), "msPlayed", 1000, "source", "ALBUM"), token),
                HttpStatus.NOT_FOUND, "not-found");
        assertProblem(api.post("/api/v1/activity/plays", Map.of("trackId", draft, "msPlayed", 1000, "source", "ALBUM"), token),
                HttpStatus.NOT_FOUND, "not-found");
        assertThat(api.post("/api/v1/activity/plays", Map.of("trackId", track, "msPlayed", 1000, "source", "ALBUM"), null)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        UUID playId = UUID.randomUUID();
        play(user, playId, track, 1000, false, false);
        assertProblem(play(api.register(), playId, track, 2000, false, false), HttpStatus.CONFLICT, "play-mismatch");
        assertProblem(play(user, playId, ready("Other"), 2000, false, false), HttpStatus.CONFLICT, "play-mismatch");
        assertThat(api.get("/api/v1/me/recently-played", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void reportsWithoutPlayIdAreSeparatePlaybacks() {
        UUID track = ready("No id");
        JsonNode first = api.post("/api/v1/activity/plays", Map.of("trackId", track, "msPlayed", 30_000, "source", "SEARCH"),
                user.accessToken()).getBody();
        JsonNode second = api.post("/api/v1/activity/plays", Map.of("trackId", track, "msPlayed", 30_000, "source", "SEARCH"),
                user.accessToken()).getBody();
        assertThat(first.get("playId").asText()).isNotEqualTo(second.get("playId").asText());
        await().atMost(Duration.ofSeconds(10)).until(() -> playCount(track) == 2);
    }

    // ---- helpers

    private UUID ready(String title) {
        UUID track = catalog.track(album, title, 1);
        CatalogFixtures.forceReady(jdbc, track, 240_000, 0);
        return track;
    }

    private ResponseEntity<JsonNode> play(Session who, UUID playId, UUID track, int ms, boolean completed, boolean skipped) {
        return api.post("/api/v1/activity/plays", Map.of("playId", playId, "trackId", track, "msPlayed", ms,
                "source", "ALBUM", "sourceId", album, "completed", completed, "skipped", skipped), who.accessToken());
    }

    private long playCount(UUID track) {
        return api.get("/api/v1/tracks/" + track, null).getBody().get("playCount").asLong();
    }

    private List<String> outbox(UUID userId) {
        return jdbc.sql("SELECT payload::text FROM outbox_event WHERE topic = 'activity.track-played' AND event_key = :k ORDER BY seq")
                .param("k", userId.toString()).query(String.class).list();
    }

    private long processedPlays(UUID playId) {
        return jdbc.sql("SELECT count(*) FROM processed_event WHERE event_id = :id").param("id", playId).query(Long.class).single();
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        assertThat(response.getBody().get("code").asText()).isEqualTo(code);
    }
}
