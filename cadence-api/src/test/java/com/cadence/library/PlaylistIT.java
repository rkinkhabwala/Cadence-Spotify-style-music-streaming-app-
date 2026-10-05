package com.cadence.library;

import com.cadence.IntegrationTest;
import com.cadence.library.domain.FractionalIndex;
import com.cadence.support.ApiClient.Session;
import com.cadence.support.CatalogFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class PlaylistIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    private CatalogFixtures catalog;
    private UUID album;
    private Session owner;

    @BeforeEach
    void setUp() {
        catalog = new CatalogFixtures(api, api.admin().accessToken());
        album = catalog.album(catalog.artist("Playlist Artist"), "Playlist Album", LocalDate.of(2024, 3, 3));
        owner = api.register();
    }

    @Test
    void createAddThreeReorderAndSeeThePersistedOrder() {
        UUID playlist = create(owner, "Road trip", "PRIVATE");
        UUID t1 = ready("One"), t2 = ready("Two"), t3 = ready("Three");

        ResponseEntity<JsonNode> added = add(owner, playlist, List.of(t1, t2, t3), null, null);
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(added.getBody().get("trackCount").asInt()).isEqualTo(3);
        assertThat(order(owner, playlist)).containsExactly(t1, t2, t3);
        String t2Position = position(playlist, t2);

        assertThat(reorder(owner, playlist, t3, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(order(owner, playlist)).containsExactly(t3, t1, t2);
        reorder(owner, playlist, t1, t2);

        assertThat(order(owner, playlist)).containsExactly(t3, t2, t1);
        assertThat(order(owner, playlist)).as("order persisted across reads").containsExactly(t3, t2, t1);
        assertThat(position(playlist, t2)).as("moves never renumber other tracks").isEqualTo(t2Position);
        JsonNode first = api.get("/api/v1/playlists/" + playlist, owner.accessToken()).getBody().at("/tracks/items/0");
        assertThat(first.at("/track/title").asText()).isEqualTo("Three");
        assertThat(first.get("playable").asBoolean()).isTrue();
        assertThat(first.get("addedBy").asText()).isEqualTo(owner.userId().toString());
    }

    @Test
    void insertAtAPositionAndPaginate() {
        UUID playlist = create(owner, "Positions", "PRIVATE");
        UUID t1 = ready("P1"), t2 = ready("P2"), t3 = ready("P3"), t4 = ready("P4"), t5 = ready("P5"), t6 = ready("P6");
        add(owner, playlist, List.of(t1, t2, t3, t4), null, null);

        add(owner, playlist, List.of(t5, t6), 1, null);

        assertThat(order(owner, playlist)).containsExactly(t1, t5, t6, t2, t3, t4);
        JsonNode page1 = api.get("/api/v1/playlists/" + playlist + "?limit=4", owner.accessToken()).getBody().get("tracks");
        JsonNode page2 = api.get("/api/v1/playlists/" + playlist + "?limit=4&cursor=" + page1.get("nextCursor").asText(),
                owner.accessToken()).getBody().get("tracks");
        assertThat(page1.get("items")).hasSize(4);
        assertThat(page2.get("items")).extracting(i -> i.get("trackId").asText()).containsExactly(t3.toString(), t4.toString());
        assertThat(page2.get("nextCursor").isNull()).isTrue();
        assertThat(add(owner, playlist, List.of(ready("P7")), 99, null).getBody().get("trackCount").asInt())
                .as("position beyond the end appends").isEqualTo(7);
    }

    @Test
    void addAndRemoveAreIdempotentAndOnlyAcceptReadyTracks() {
        UUID playlist = create(owner, "Idempotent", "PRIVATE");
        UUID t1 = ready("Once");
        UUID draft = catalog.track(album, "Draft", 9);

        add(owner, playlist, List.of(t1), null, null);
        ResponseEntity<JsonNode> again = add(owner, playlist, List.of(t1, t1), null, null);
        ResponseEntity<JsonNode> unknown = add(owner, playlist, List.of(UUID.randomUUID()), null, null);
        ResponseEntity<JsonNode> notReady = add(owner, playlist, List.of(draft), null, null);

        assertThat(again.getBody().get("trackCount").asInt()).isEqualTo(1);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknown.getBody().get("code").asText()).isEqualTo("unknown-track");
        assertThat(notReady.getBody().get("code").asText()).isEqualTo("unknown-track");
        assertThat(remove(owner, playlist, List.of(t1)).getBody().get("trackCount").asInt()).isZero();
        assertThat(remove(owner, playlist, List.of(t1)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(add(owner, playlist, List.of(), null, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void onlyTheOwnerCanSeePrivateAndChangeAnyPlaylist() {
        UUID playlist = create(owner, "Mine", "PRIVATE");
        Session other = api.register();

        assertThat(api.get("/api/v1/playlists/" + playlist, other.accessToken()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(add(other, playlist, List.of(ready("X")), null, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        patch(owner, playlist, etag(owner, playlist), Map.of("visibility", "PUBLIC"));

        assertThat(api.get("/api/v1/playlists/" + playlist, other.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> otherPatch = patch(other, playlist, etag(owner, playlist), Map.of("name", "Hijacked"));
        assertThat(otherPatch.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(otherPatch.getBody().get("code").asText()).isEqualTo("forbidden");
        assertThat(add(other, playlist, List.of(ready("Y")), null, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(reorder(other, playlist, UUID.randomUUID(), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.delete("/api/v1/playlists/" + playlist, null, other.accessToken()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.get("/api/v1/playlists/" + playlist, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(api.delete("/api/v1/playlists/" + playlist, null, owner.accessToken()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.get("/api/v1/playlists/" + playlist, owner.accessToken()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void patchUsesIfMatchVersions() {
        UUID playlist = create(owner, "Versioned", "PRIVATE");
        String etag = etag(owner, playlist);

        ResponseEntity<JsonNode> missing = patch(owner, playlist, null, Map.of("name", "No If-Match"));
        ResponseEntity<JsonNode> ok = patch(owner, playlist, etag, Map.of("name", "Renamed", "description", "Now with words"));
        ResponseEntity<JsonNode> stale = patch(owner, playlist, etag, Map.of("name", "Stale"));
        ResponseEntity<JsonNode> staleAdd = add(owner, playlist, List.of(ready("Z")), null, etag);

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("name").asText()).isEqualTo("Renamed");
        assertThat(ok.getHeaders().getETag()).isNotEqualTo(etag);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
        assertThat(stale.getBody().get("code").asText()).isEqualTo("version-mismatch");
        assertThat(staleAdd.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
        assertThat(add(owner, playlist, List.of(ready("Z2")), null, ok.getHeaders().getETag()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(api.get("/api/v1/playlists/" + playlist, owner.accessToken()).getBody().get("name").asText()).isEqualTo("Renamed");
    }

    @Test
    void concurrentEditsNeverLoseWrites() throws Exception {
        UUID playlist = create(owner, "Race", "PRIVATE");
        List<UUID> tracks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tracks.add(ready("Race " + i));
        }
        List<Callable<HttpStatus>> calls = tracks.stream().<Callable<HttpStatus>>map(t ->
                () -> (HttpStatus) add(owner, playlist, List.of(t), null, null).getStatusCode()).toList();

        List<HttpStatus> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (Future<HttpStatus> f : pool.invokeAll(calls)) {
                statuses.add(f.get());
            }
        }

        long succeeded = statuses.stream().filter(s -> s == HttpStatus.OK).count();
        assertThat(statuses).allMatch(s -> s == HttpStatus.OK || s == HttpStatus.CONFLICT);
        JsonNode body = api.get("/api/v1/playlists/" + playlist + "?limit=100", owner.accessToken()).getBody();
        assertThat(body.get("trackCount").asLong()).isEqualTo(succeeded);
        assertThat(body.at("/tracks/items").size()).isEqualTo((int) succeeded);
        assertThat(body.get("version").asLong()).isEqualTo(succeeded);
    }

    @Test
    void playlistsHoldAtMostTenThousandTracks() {
        UUID playlist = create(owner, "Huge", "PRIVATE");
        List<String> keys = FractionalIndex.between(null, null, 10_000);
        Timestamp now = Timestamp.from(Instant.now());
        List<Object[]> rows = keys.stream().map(k -> new Object[]{playlist, UUID.randomUUID(), k, owner.userId(), now}).toList();
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).batchUpdate(
                "INSERT INTO playlist_tracks (playlist_id, track_id, position, added_by, added_at) VALUES (?, ?, ?, ?, ?)", rows);
        jdbc.sql("UPDATE playlists SET track_count = 10000 WHERE id = :id").param("id", playlist).update();

        ResponseEntity<JsonNode> full = add(owner, playlist, List.of(ready("One too many")), null, null);

        assertThat(full.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(full.getBody().get("code").asText()).isEqualTo("playlist-full");
    }

    @Test
    void myPlaylistsNewestFirst() {
        Session user = api.register();
        UUID first = create(user, "First", "PRIVATE");
        UUID second = create(user, "Second", "PUBLIC");

        JsonNode mine = api.get("/api/v1/me/playlists", user.accessToken()).getBody();

        assertThat(mine.get("items")).extracting(p -> p.get("id").asText()).containsExactly(second.toString(), first.toString());
        assertThat(mine.at("/items/0/visibility").asText()).isEqualTo("PUBLIC");
        ResponseEntity<JsonNode> invalid = api.post("/api/v1/playlists", Map.of("name", ""), user.accessToken());
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Autowired
    javax.sql.DataSource dataSource;

    private UUID ready(String title) {
        UUID track = catalog.track(album, title, 1);
        CatalogFixtures.forceReady(jdbc, track, 200_000, 0);
        return track;
    }

    private UUID create(Session user, String name, String visibility) {
        ResponseEntity<JsonNode> response = api.post("/api/v1/playlists", Map.of("name", name, "visibility", visibility),
                user.accessToken());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"0\"");
        return UUID.fromString(response.getBody().get("id").asText());
    }

    private String etag(Session user, UUID playlist) {
        return api.get("/api/v1/playlists/" + playlist, user.accessToken()).getHeaders().getETag();
    }

    private ResponseEntity<JsonNode> add(Session user, UUID playlist, List<UUID> tracks, Integer position, String ifMatch) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("trackIds", tracks));
        if (position != null) {
            body.put("position", position);
        }
        return api.exchange(HttpMethod.POST, "/api/v1/playlists/" + playlist + "/tracks", body, user.accessToken(), ifMatch(ifMatch));
    }

    private ResponseEntity<JsonNode> remove(Session user, UUID playlist, List<UUID> tracks) {
        return api.delete("/api/v1/playlists/" + playlist + "/tracks", Map.of("trackIds", tracks), user.accessToken());
    }

    private ResponseEntity<JsonNode> reorder(Session user, UUID playlist, UUID track, UUID after) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("trackId", track);
        body.put("afterTrackId", after);
        return api.put("/api/v1/playlists/" + playlist + "/tracks/reorder", body, user.accessToken());
    }

    private ResponseEntity<JsonNode> patch(Session user, UUID playlist, String ifMatch, Map<String, Object> body) {
        return api.exchange(HttpMethod.PATCH, "/api/v1/playlists/" + playlist, body, user.accessToken(), ifMatch(ifMatch));
    }

    private List<UUID> order(Session user, UUID playlist) {
        List<UUID> ids = new ArrayList<>();
        api.get("/api/v1/playlists/" + playlist + "?limit=100", user.accessToken()).getBody().at("/tracks/items")
                .forEach(i -> ids.add(UUID.fromString(i.get("trackId").asText())));
        return ids;
    }

    private String position(UUID playlist, UUID track) {
        return jdbc.sql("SELECT position FROM playlist_tracks WHERE playlist_id = :p AND track_id = :t")
                .param("p", playlist).param("t", track).query(String.class).single();
    }

    private static HttpHeaders ifMatch(String value) {
        HttpHeaders headers = new HttpHeaders();
        if (value != null) {
            headers.setIfMatch(value);
        }
        return headers;
    }
}
