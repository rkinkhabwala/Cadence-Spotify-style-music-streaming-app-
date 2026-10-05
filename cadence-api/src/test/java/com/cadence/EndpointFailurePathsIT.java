package com.cadence;

import com.cadence.support.ApiClient.Session;
import com.cadence.support.CatalogFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failure paths for endpoints whose main test class covers only the happy path, so that every endpoint has at
 * least one of each (spec 9 / CLAUDE.md testing rule).
 */
class EndpointFailurePathsIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    private Session user;
    private String admin;

    @BeforeEach
    void setUp() {
        user = api.register();
        admin = api.admin().accessToken();
    }

    @Test
    void identityEndpoints() {
        assertThat(api.post("/api/v1/auth/logout", null, null).getStatusCode())
                .as("logout without the CSRF header").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.post("/api/v1/auth/login", Map.of("email", "x"), null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // only GET is public; any other method falls under "authenticated" before routing
        assertThat(api.post("/.well-known/jwks.json", null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void catalogEndpoints() {
        assertThat(api.get("/api/v1/genres?cursor=%%%", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.get("/api/v1/admin/tracks/" + UUID.randomUUID(), admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.patch("/api/v1/admin/artists/" + UUID.randomUUID(), Map.of("name", "x"), admin).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.patch("/api/v1/admin/albums/" + UUID.randomUUID(), Map.of("title", "x"), admin).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.delete("/api/v1/admin/tracks/" + UUID.randomUUID(), null, admin).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.post("/api/v1/admin/tracks/" + UUID.randomUUID() + "/upload-complete", null, admin).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void streamingEndpoints() {
        ResponseEntity<JsonNode> unknownVariant = api.get("/api/v1/playback/" + UUID.randomUUID() + "/999k/index.m3u8?token=x", null);
        assertThat(unknownVariant.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED); // token checked first
        assertThat(api.get("/api/v1/tracks/" + UUID.randomUUID() + "/stream", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void playlistEndpoints() {
        CatalogFixtures catalog = new CatalogFixtures(api, admin);
        UUID album = catalog.album(catalog.artist("Failures"), "Failures", LocalDate.of(2020, 1, 1));
        UUID t1 = catalog.track(album, "F1", 1);
        UUID t2 = catalog.track(album, "F2", 2);
        CatalogFixtures.forceReady(jdbc, t1, 1000, 0);
        CatalogFixtures.forceReady(jdbc, t2, 1000, 0);
        UUID playlist = UUID.fromString(api.post("/api/v1/playlists", Map.of("name", "F", "visibility", "PUBLIC"),
                user.accessToken()).getBody().get("id").asText());
        api.post("/api/v1/playlists/" + playlist + "/tracks", Map.of("trackIds", List.of(t1, t2)), user.accessToken());
        Session stranger = api.register();

        assertThat(api.get("/api/v1/me/playlists?cursor=garbage!", user.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.delete("/api/v1/playlists/" + playlist + "/tracks", Map.of("trackIds", List.of(t1)),
                stranger.accessToken()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.delete("/api/v1/playlists/" + UUID.randomUUID(), null, user.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(reorder(playlist, UUID.randomUUID(), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<JsonNode> unknownAnchor = reorder(playlist, t1, UUID.randomUUID());
        assertThat(unknownAnchor.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknownAnchor.getBody().get("code").asText()).isEqualTo("unknown-after-track");
        assertThat(reorder(playlist, t1, t1).getBody().get("code").asText()).isEqualTo("invalid-reorder");
        assertThat(api.exchange(HttpMethod.PUT, "/api/v1/playlists/" + playlist + "/tracks/reorder", Map.of(),
                user.accessToken(), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void libraryEndpoints() {
        UUID any = UUID.randomUUID();

        assertThat(api.get("/api/v1/me/likes/tracks", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(api.get("/api/v1/me/likes/tracks?cursor=garbage!", user.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.delete("/api/v1/me/likes/tracks/" + any, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(api.delete("/api/v1/me/following/artists/" + any, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(api.delete("/api/v1/me/albums/" + any, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(api.get("/api/v1/me/albums", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(api.put("/api/v1/me/likes/tracks/not-a-uuid", null, user.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<JsonNode> reorder(UUID playlist, UUID track, UUID after) {
        Map<String, Object> body = new HashMap<>();
        body.put("trackId", track);
        body.put("afterTrackId", after);
        return api.put("/api/v1/playlists/" + playlist + "/tracks/reorder", body, user.accessToken());
    }
}
