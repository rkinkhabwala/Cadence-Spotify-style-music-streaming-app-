package com.cadence.library;

import com.cadence.IntegrationTest;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/** Collaborative playlists (spec 9 Phase 3; D94, D95). */
class CollaborativePlaylistIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    private CatalogFixtures catalog;
    private UUID album;
    private Session owner;
    private Session collaborator;

    @BeforeEach
    void setUp() {
        catalog = new CatalogFixtures(api, api.admin().accessToken());
        album = catalog.album(catalog.artist("Collab Artist"), "Collab Album", LocalDate.of(2024, 6, 6));
        owner = api.register();
        collaborator = api.register();
    }

    @Test
    void anInvitedUserJoinsAndEditsTracksButCannotManageThePlaylist() {
        UUID playlist = create("Road trip", "PRIVATE");
        assertThat(code(api.post(path(playlist, "/invite"), null, owner.accessToken()))).isEqualTo("not-collaborative");
        makeCollaborative(playlist, true);
        String token = api.post(path(playlist, "/invite"), null, owner.accessToken()).getBody().get("inviteToken").asText();
        assertThat(api.post(path(playlist, "/invite"), null, owner.accessToken()).getBody().get("inviteToken").asText())
                .as("idempotent").isEqualTo(token);

        assertThat(api.get(path(playlist, ""), collaborator.accessToken()).getStatusCode())
                .as("private and not joined yet").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(join(collaborator, playlist, "wrong-token"))).isEqualTo("invalid-invite");
        assertThat(join(collaborator, playlist, token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(join(collaborator, playlist, token).getStatusCode()).as("idempotent").isEqualTo(HttpStatus.OK);

        JsonNode detail = api.get(path(playlist, ""), collaborator.accessToken()).getBody();
        assertThat(detail.get("role").asText()).isEqualTo("COLLABORATOR");
        assertThat(detail.get("collaboratorCount").asInt()).isEqualTo(1);
        UUID t1 = ready("C1"), t2 = ready("C2");
        assertThat(add(collaborator, playlist, t1).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(add(owner, playlist, t2).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api.put(path(playlist, "/tracks/reorder"), Map.of("trackId", t2), collaborator.accessToken())
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode items = api.get(path(playlist, ""), owner.accessToken()).getBody().at("/tracks/items");
        assertThat(items.get(0).get("trackId").asText()).isEqualTo(t2.toString());
        assertThat(items.get(1).get("addedBy").asText()).isEqualTo(collaborator.userId().toString());
        assertThat(api.delete(path(playlist, "/tracks"), Map.of("trackIds", List.of(t2)), collaborator.accessToken())
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        long version = api.get(path(playlist, ""), owner.accessToken()).getBody().get("version").asLong();
        assertThat(patch(collaborator, playlist, version, Map.of("name", "Mine now")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.delete(path(playlist, ""), null, collaborator.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.post(path(playlist, "/invite"), null, collaborator.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(ids(api.get("/api/v1/me/playlists", collaborator.accessToken()).getBody().get("items")))
                .contains(playlist.toString());
        JsonNode people = api.get(path(playlist, "/collaborators"), owner.accessToken()).getBody().get("items");
        assertThat(people).hasSize(1);
        assertThat(people.get(0).get("userId").asText()).isEqualTo(collaborator.userId().toString());
        assertThat(people.get(0).get("displayName").asText()).isEqualTo("Test User");
    }

    @Test
    void listenersOfAPublicCollaborativePlaylistCanReadButNotEdit() {
        UUID playlist = create("Open mix", "PUBLIC");
        makeCollaborative(playlist, true);
        Session listener = api.register();

        assertThat(api.get(path(playlist, ""), listener.accessToken()).getBody().get("role").asText()).isEqualTo("LISTENER");
        assertThat(add(listener, playlist, ready("L1")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.get(path(playlist, "/collaborators"), listener.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.get(path(playlist, ""), owner.accessToken()).getBody().get("role").asText()).isEqualTo("OWNER");
    }

    @Test
    void revokingTheLinkLeavingRemovingAndTurningCollaborationOff() {
        UUID playlist = create("Shared", "PRIVATE");
        makeCollaborative(playlist, true);
        String token = invite(playlist);
        join(collaborator, playlist, token);
        Session second = api.register();
        join(second, playlist, token);

        assertThat(api.delete(path(playlist, "/invite"), null, owner.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(code(join(api.register(), playlist, token))).as("revoked link").isEqualTo("invalid-invite");
        assertThat(add(collaborator, playlist, ready("R1")).getStatusCode()).as("members stay").isEqualTo(HttpStatus.OK);
        assertThat(invite(playlist)).as("a new link").isNotEqualTo(token);

        assertThat(api.delete(path(playlist, "/collaborators/" + second.userId()), null, collaborator.accessToken())
                .getStatusCode()).as("only the owner removes others").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.delete(path(playlist, "/collaborators/" + collaborator.userId()), null, collaborator.accessToken())
                .getStatusCode()).as("leave").isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.get(path(playlist, ""), collaborator.accessToken()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.delete(path(playlist, "/collaborators/" + second.userId()), null, owner.accessToken())
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.delete(path(playlist, "/collaborators/" + second.userId()), null, owner.accessToken())
                .getStatusCode()).as("idempotent").isEqualTo(HttpStatus.NO_CONTENT);

        Session third = api.register();
        join(third, playlist, invite(playlist));
        makeCollaborative(playlist, false);
        assertThat(add(third, playlist, ready("R2")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ids(api.get("/api/v1/me/playlists", third.accessToken()).getBody().get("items")))
                .doesNotContain(playlist.toString());
        assertThat(api.get(path(playlist, ""), owner.accessToken()).getBody().get("collaboratorCount").asInt()).isZero();
    }

    /** Spec 9 Phase 3 AC5: two collaborators editing the same playlist concurrently don't lose writes. */
    @Test
    void twoCollaboratorsEditingConcurrentlyLoseNoWrites() throws Exception {
        UUID playlist = create("Party", "PRIVATE");
        makeCollaborative(playlist, true);
        join(collaborator, playlist, invite(playlist));
        long before = api.get(path(playlist, ""), owner.accessToken()).getBody().get("version").asLong();
        List<UUID> tracks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tracks.add(ready("Party " + i));
        }

        List<Callable<ResponseEntity<JsonNode>>> adds = new ArrayList<>();
        for (int i = 0; i < tracks.size(); i++) {
            Session who = i % 2 == 0 ? owner : collaborator;
            UUID track = tracks.get(i);
            adds.add(() -> add(who, playlist, track));
        }
        List<HttpStatus> statuses = runConcurrently(adds);

        assertThat(statuses).containsOnly(HttpStatus.OK);
        JsonNode body = api.get(path(playlist, "?limit=100"), owner.accessToken()).getBody();
        assertThat(body.get("trackCount").asInt()).isEqualTo(20);
        assertThat(new HashSet<>(ids(body.at("/tracks/items"), "trackId")))
                .containsExactlyInAnyOrderElementsOf(tracks.stream().map(UUID::toString).toList());
        assertThat(body.get("version").asLong()).as("one version per write").isEqualTo(before + 20);
        Map<String, Long> byUser = new HashMap<>();
        body.at("/tracks/items").forEach(i -> byUser.merge(i.get("addedBy").asText(), 1L, Long::sum));
        assertThat(byUser).containsEntry(owner.userId().toString(), 10L).containsEntry(collaborator.userId().toString(), 10L);

        // concurrent moves to the top by both users: all succeed, every track still there exactly once
        List<Callable<ResponseEntity<JsonNode>>> moves = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Session who = i % 2 == 0 ? owner : collaborator;
            UUID track = tracks.get(19 - i);
            moves.add(() -> api.put(path(playlist, "/tracks/reorder"), Map.of("trackId", track), who.accessToken()));
        }
        assertThat(runConcurrently(moves)).containsOnly(HttpStatus.OK);
        JsonNode after = api.get(path(playlist, "?limit=100"), owner.accessToken()).getBody();
        assertThat(after.at("/tracks/items")).hasSize(20);
        assertThat(new HashSet<>(ids(after.at("/tracks/items"), "trackId"))).hasSize(20);
        assertThat(after.get("version").asLong()).isEqualTo(before + 30);
    }

    @Test
    void aStaleIfMatchIsStill412AndNotRetried() {
        UUID playlist = create("Strict", "PRIVATE");
        makeCollaborative(playlist, true);
        join(collaborator, playlist, invite(playlist));
        long version = api.get(path(playlist, ""), owner.accessToken()).getBody().get("version").asLong();
        add(owner, playlist, ready("S1"));

        HttpHeaders stale = new HttpHeaders();
        stale.setIfMatch("\"" + version + "\"");
        ResponseEntity<JsonNode> response = api.exchange(HttpMethod.POST, path(playlist, "/tracks"),
                Map.of("trackIds", List.of(ready("S2"))), collaborator.accessToken(), stale);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
    }

    // ------------------------------------------------------------------------------------------------------------

    private UUID create(String name, String visibility) {
        ResponseEntity<JsonNode> response = api.post("/api/v1/playlists", Map.of("name", name, "visibility", visibility),
                owner.accessToken());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(response.getBody().get("id").asText());
    }

    private void makeCollaborative(UUID playlist, boolean on) {
        long version = api.get(path(playlist, ""), owner.accessToken()).getBody().get("version").asLong();
        assertThat(patch(owner, playlist, version, Map.of("collaborative", on)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String invite(UUID playlist) {
        return api.post(path(playlist, "/invite"), null, owner.accessToken()).getBody().get("inviteToken").asText();
    }

    private ResponseEntity<JsonNode> join(Session who, UUID playlist, String token) {
        return api.post(path(playlist, "/collaborators"), Map.of("inviteToken", token), who.accessToken());
    }

    private ResponseEntity<JsonNode> add(Session who, UUID playlist, UUID track) {
        return api.post(path(playlist, "/tracks"), Map.of("trackIds", List.of(track)), who.accessToken());
    }

    private ResponseEntity<JsonNode> patch(Session who, UUID playlist, long version, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setIfMatch("\"" + version + "\"");
        return api.exchange(HttpMethod.PATCH, path(playlist, ""), body, who.accessToken(), headers);
    }

    private UUID ready(String title) {
        UUID track = catalog.track(album, title, 1);
        CatalogFixtures.forceReady(jdbc, track, 120_000, 0);
        return track;
    }

    private static List<HttpStatus> runConcurrently(List<Callable<ResponseEntity<JsonNode>>> calls) throws Exception {
        List<HttpStatus> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(calls.size())) {
            for (Future<ResponseEntity<JsonNode>> f : pool.invokeAll(calls)) {
                ResponseEntity<JsonNode> response = f.get();
                assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
                statuses.add((HttpStatus) response.getStatusCode());
            }
        }
        return statuses;
    }

    private static String path(UUID playlist, String suffix) {
        return "/api/v1/playlists/" + playlist + suffix;
    }

    private static String code(ResponseEntity<JsonNode> response) {
        return response.getBody().get("code").asText();
    }

    private static List<String> ids(JsonNode items) {
        return ids(items, "id");
    }

    private static List<String> ids(JsonNode items, String field) {
        return StreamSupport.stream(items.spliterator(), false).map(i -> i.get(field).asText()).toList();
    }
}
