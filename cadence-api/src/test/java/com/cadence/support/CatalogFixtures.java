package com.cadence.support;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Creates catalog entities through the admin API. */
public class CatalogFixtures {

    private final ApiClient api;
    private final String adminToken;

    public CatalogFixtures(ApiClient api, String adminToken) {
        this.api = api;
        this.adminToken = adminToken;
    }

    public UUID artist(String name) {
        return id(api.post("/api/v1/admin/artists", Map.of("name", name), adminToken));
    }

    public UUID album(UUID artistId, String title, LocalDate releaseDate) {
        return album(artistId, title, releaseDate, "Rock");
    }

    public UUID album(UUID artistId, String title, LocalDate releaseDate, String... genres) {
        return id(api.post("/api/v1/admin/albums", Map.of("title", title, "artistId", artistId,
                "releaseDate", releaseDate.toString(), "type", "ALBUM", "genres", java.util.List.of(genres)), adminToken));
    }

    public UUID track(UUID albumId, String title, int number) {
        return id(api.post("/api/v1/admin/tracks", Map.of("title", title, "albumId", albumId, "trackNumber", number),
                adminToken));
    }

    /**
     * Test shortcut for catalog-only tests: marks a track READY directly in the catalog's own table (the real path,
     * via the transcoder's event, is covered by the streaming and end-to-end tests).
     */
    public static void forceReady(JdbcClient jdbc, UUID trackId, int durationMs, long playCount) {
        jdbc.sql("UPDATE tracks SET status = 'READY', duration_ms = :d, play_count = :p WHERE id = :id")
                .param("d", durationMs).param("p", playCount).param("id", trackId).update();
    }

    public static void forceStatus(JdbcClient jdbc, UUID trackId, String status) {
        jdbc.sql("UPDATE tracks SET status = :s WHERE id = :id").param("s", status).param("id", trackId).update();
    }

    private static UUID id(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(response.getBody().get("id").asText());
    }
}
