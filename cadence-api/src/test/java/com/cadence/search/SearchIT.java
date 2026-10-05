package com.cadence.search;

import com.cadence.IntegrationTest;
import com.cadence.common.storage.ObjectStorage;
import com.cadence.support.ApiClient.Session;
import com.cadence.support.CatalogFixtures;
import com.cadence.support.StreamingFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;

import java.net.URLEncoder;
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

class SearchIT extends IntegrationTest {

    /** Spec 9 Phase 2 AC2: a catalog edit is reflected in search within 5 seconds. */
    private static final Duration INDEXING_SLA = Duration.ofSeconds(5);

    @Autowired
    ObjectStorage storage;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JdbcClient jdbc;

    private String adminToken;
    private CatalogFixtures catalog;
    private StreamingFixtures streaming;
    private Session user;

    @BeforeEach
    void setUp() {
        adminToken = api.admin().accessToken();
        catalog = new CatalogFixtures(api, adminToken);
        streaming = new StreamingFixtures(api, adminToken, storage, kafka, objectMapper, jdbc);
        user = api.register();
    }

    @Test
    void misspelledArtistNameIsSuggestedByFuzzyMatching() {
        UUID beatlz = catalog.artist("The Beatlz");

        await().atMost(INDEXING_SLA).untilAsserted(() -> {
            JsonNode suggestions = suggest("beatls");
            assertThat(suggestions.get("items")).anySatisfy(s -> {
                assertThat(s.get("type").asText()).isEqualTo("artist");
                assertThat(s.get("id").asText()).isEqualTo(beatlz.toString());
                assertThat(s.get("text").asText()).isEqualTo("The Beatlz");
            });
        });
        assertThat(search("beatls", "artist").at("/artists/items").findValuesAsText("id")).contains(beatlz.toString());
        assertThat(search("beatl", "artist").at("/artists/items").findValuesAsText("id")).as("prefix").contains(beatlz.toString());
    }

    @Test
    void catalogEditIsReflectedInSearchWithinFiveSeconds() {
        String before = word(), after = word();
        UUID artist = catalog.artist("Artist " + before);
        await().atMost(INDEXING_SLA).until(() -> ids(search(before, "artist"), "artists").contains(artist.toString()));

        long start = System.nanoTime();
        assertThat(api.patch("/api/v1/admin/artists/" + artist, Map.of("name", "Artist " + after), adminToken)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        await().atMost(INDEXING_SLA).pollInterval(Duration.ofMillis(100)).until(() ->
                ids(search(after, "artist"), "artists").contains(artist.toString())
                        && !ids(search(before, "artist"), "artists").contains(artist.toString()));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(INDEXING_SLA);
    }

    @Test
    void onlyReadyTracksAreSearchableAndHitsCarryTheTrackSummary() {
        String token = word();
        UUID artist = catalog.artist("Singer " + token);
        UUID album = catalog.album(artist, "Record " + token, LocalDate.of(2024, 2, 2));
        UUID track = catalog.track(album, "Song " + token, 1);

        await().atMost(INDEXING_SLA).until(() -> ids(search(token, "album"), "albums").contains(album.toString()));
        assertThat(ids(search(token, "track"), "tracks")).as("DRAFT").doesNotContain(track.toString());

        streaming.makeReady(track);
        await().atMost(INDEXING_SLA).until(() -> ids(search(token, "track"), "tracks").contains(track.toString()));
        JsonNode hit = search(token, "track").at("/tracks/items/0");
        assertThat(hit.get("title").asText()).isEqualTo("Song " + token);
        assertThat(hit.at("/album/id").asText()).isEqualTo(album.toString());
        assertThat(hit.at("/artists/0/name").asText()).isEqualTo("Singer " + token);
        assertThat(hit.get("durationMs").asInt()).isEqualTo(StreamingFixtures.DURATION_MS);

        assertThat(api.delete("/api/v1/admin/tracks/" + track, null, adminToken).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        await().atMost(INDEXING_SLA).until(() -> !ids(search(token, "track"), "tracks").contains(track.toString()));
    }

    @Test
    void renamingAnArtistOrAlbumUpdatesTheTracksThatEmbedTheirNames() {
        String oldName = word(), newArtist = word(), newAlbum = word();
        UUID artist = catalog.artist("Band " + oldName);
        UUID album = catalog.album(artist, "Album " + oldName, LocalDate.of(2020, 1, 1));
        UUID track = catalog.track(album, "Tune " + word(), 1);
        streaming.makeReady(track);
        await().atMost(INDEXING_SLA).until(() -> ids(search(oldName, "track"), "tracks").contains(track.toString()));

        api.patch("/api/v1/admin/artists/" + artist, Map.of("name", "Band " + newArtist), adminToken);
        api.patch("/api/v1/admin/albums/" + album, Map.of("title", "Album " + newAlbum), adminToken);

        await().atMost(INDEXING_SLA).untilAsserted(() -> {
            assertThat(ids(search(newArtist, "track"), "tracks")).as("by new artist name").contains(track.toString());
            assertThat(ids(search(newAlbum, "track"), "tracks")).as("by new album title").contains(track.toString());
            assertThat(ids(search(oldName, "track"), "tracks")).as("old names gone").doesNotContain(track.toString());
            JsonNode albumHit = search(newAlbum, "album").at("/albums/items/0");
            assertThat(albumHit.at("/artist/name").asText()).isEqualTo("Band " + newArtist);
        });
    }

    @Test
    void resultsAreGroupedByTypeAndEachTypePagesWithItsOwnCursor() {
        String token = word();
        UUID artist = catalog.artist("Group " + token);
        List<String> albums = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            albums.add(catalog.album(artist, "Volume " + i + " " + token, LocalDate.of(2020 + i, 1, 1)).toString());
        }
        await().atMost(INDEXING_SLA).until(() -> ids(search(token, "album"), "albums").size() == 3);

        JsonNode all = search(token, null);
        assertThat(all.has("tracks") && all.has("artists") && all.has("albums") && all.has("playlists")).isTrue();
        assertThat(all.get("query").asText()).isEqualTo(token);
        assertThat(ids(all, "artists")).containsExactly(artist.toString());

        JsonNode onlyAlbums = search(token, "album");
        assertThat(onlyAlbums.has("tracks")).isFalse();
        JsonNode page1 = get("/api/v1/search?types=album&limit=2&q=" + token).getBody();
        assertThat(page1.at("/albums/items")).hasSize(2);
        JsonNode page2 = get("/api/v1/search?types=album&limit=2&q=" + token + "&cursor="
                + page1.at("/albums/nextCursor").asText()).getBody();
        assertThat(page2.at("/albums/items")).hasSize(1);
        assertThat(page2.at("/albums/nextCursor").isNull()).isTrue();
        List<String> paged = new ArrayList<>(ids(page1, "albums"));
        paged.addAll(ids(page2, "albums"));
        assertThat(paged).containsExactlyInAnyOrderElementsOf(albums);
    }

    @Test
    void invalidRequestsAreRejected() {
        assertProblem(get("/api/v1/search?q=   "), HttpStatus.BAD_REQUEST, "invalid-query");
        assertProblem(get("/api/v1/search"), HttpStatus.BAD_REQUEST, "invalid-query");
        assertProblem(get("/api/v1/search?q=" + "x".repeat(101)), HttpStatus.BAD_REQUEST, "invalid-query");
        assertProblem(get("/api/v1/search?q=a&types=track,podcast"), HttpStatus.BAD_REQUEST, "invalid-search-type");
        assertProblem(get("/api/v1/search?q=a&types=artist,album&cursor=WzFd"), HttpStatus.BAD_REQUEST, "cursor-needs-single-type");
        assertProblem(get("/api/v1/search?q=a&types=artist&cursor=not-a-cursor!"), HttpStatus.BAD_REQUEST, "invalid-cursor");
        assertProblem(get("/api/v1/search/suggest?q="), HttpStatus.BAD_REQUEST, "invalid-query");
        assertThat(api.get("/api/v1/search?q=a", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(api.get("/api/v1/search/suggest?q=a", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void publicPlaylistsAreSearchableWithTheirOwnerAndPrivateOnesAreNot() {
        String token = word();
        String publicId = api.post("/api/v1/playlists", Map.of("name", "Mix " + token, "visibility", "PUBLIC"),
                user.accessToken()).getBody().get("id").asText();
        String privateId = api.post("/api/v1/playlists", Map.of("name", "Secret " + token), user.accessToken())
                .getBody().get("id").asText();

        await().atMost(INDEXING_SLA).until(() -> ids(search(token, "playlist"), "playlists").contains(publicId));
        JsonNode hit = search(token, "playlist").at("/playlists/items/0");
        assertThat(hit.at("/owner/id").asText()).isEqualTo(user.userId().toString());
        assertThat(hit.at("/owner/displayName").asText()).isEqualTo("Test User");
        assertThat(ids(search(token, "playlist"), "playlists")).doesNotContain(privateId);

        HttpHeaders ifMatch = new HttpHeaders();
        ifMatch.setIfMatch("\"" + api.get("/api/v1/playlists/" + publicId, user.accessToken()).getBody().get("version").asText() + "\"");
        api.exchange(org.springframework.http.HttpMethod.PATCH, "/api/v1/playlists/" + publicId,
                Map.of("visibility", "PRIVATE"), user.accessToken(), ifMatch);
        await().atMost(INDEXING_SLA).until(() -> !ids(search(token, "playlist"), "playlists").contains(publicId));
    }

    @Test
    void searchIsRateLimitedPerUser() {
        int limited = 0;
        String retryAfter = null;
        for (int i = 0; i < 100; i++) {
            ResponseEntity<JsonNode> response = get("/api/v1/search/suggest?q=zz");
            if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                limited++;
                retryAfter = response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            }
        }
        assertThat(limited).as("100 calls with 30/s allowed").isPositive();
        assertThat(retryAfter).isEqualTo("1");
        // another user has their own bucket
        assertThat(api.get("/api/v1/search/suggest?q=zz", api.register().accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void suggestAnswersUnder100MillisecondsAtP95() throws InterruptedException {
        catalog.artist("Latency " + word());
        List<Long> millis = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            long start = System.nanoTime();
            ResponseEntity<JsonNode> response = get("/api/v1/search/suggest?q=lat" + (char) ('a' + i % 26));
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            if (i >= 10) {           // the first calls warm up connections and caches
                millis.add(elapsed);
            }
            Thread.sleep(40);        // stay under the 30/s rate limit
        }
        millis.sort(Long::compare);
        long p95 = millis.get((int) Math.ceil(millis.size() * 0.95) - 1);
        assertThat(p95).as("p95 of %s", millis).isLessThan(100);
    }

    @Test
    void adminCanRebuildTheIndicesFromAFullReplay() {
        String token = word();
        UUID artist = catalog.artist("Reindexed " + token);
        await().atMost(INDEXING_SLA).until(() -> ids(search(token, "artist"), "artists").contains(artist.toString()));

        assertThat(api.post("/api/v1/admin/search/reindex", null, user.accessToken()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> reindex = api.post("/api/v1/admin/search/reindex", null, adminToken);
        assertThat(reindex.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(reindex.getBody().get("replayedEvents").asInt()).isPositive();

        await().atMost(Duration.ofSeconds(30)).until(() -> ids(search(token, "artist"), "artists").contains(artist.toString()));
    }

    // ---- helpers

    private JsonNode search(String q, String types) {
        ResponseEntity<JsonNode> response = get("/api/v1/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
                + (types == null ? "" : "&types=" + types));
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private JsonNode suggest(String q) {
        ResponseEntity<JsonNode> response = get("/api/v1/search/suggest?q=" + q);
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> get(String path) {
        return api.get(path, user.accessToken());
    }

    private static List<String> ids(JsonNode results, String group) {
        List<String> ids = new ArrayList<>();
        results.path(group).path("items").forEach(i -> ids.add(i.get("id").asText()));
        return ids;
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        assertThat(response.getBody().get("code").asText()).isEqualTo(code);
    }

    /** A random 10-letter word: unique enough that fuzzy matching never confuses it with another test's data. */
    private static String word() {
        StringBuilder word = new StringBuilder("q");
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 9; i++) {
            word.append((char) ('a' + random.nextInt(26)));
        }
        return word.toString();
    }
}
