package com.cadence.catalog;

import com.cadence.IntegrationTest;
import com.cadence.support.CatalogFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminCatalogIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    private String admin;
    private CatalogFixtures catalog;

    @BeforeEach
    void setUp() {
        admin = api.admin().accessToken();
        catalog = new CatalogFixtures(api, admin);
    }

    @Test
    void artistCrudAndPublicRead() {
        ResponseEntity<JsonNode> created = api.post("/api/v1/admin/artists",
                Map.of("name", "  Nova Lights ", "bio", "Synth duo", "verified", true), admin);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString(created.getBody().get("id").asText());
        assertThat(created.getHeaders().getLocation()).hasToString("/api/v1/artists/" + id);
        assertThat(created.getBody().get("name").asText()).isEqualTo("Nova Lights");

        ResponseEntity<JsonNode> patched = api.patch("/api/v1/admin/artists/" + id, Map.of("bio", ""), admin);
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patched.getBody().get("bio").isNull()).as("empty string clears").isTrue();
        assertThat(patched.getBody().get("verified").asBoolean()).isTrue();

        JsonNode publicView = api.get("/api/v1/artists/" + id, null).getBody();
        assertThat(publicView.get("name").asText()).isEqualTo("Nova Lights");
        assertThat(publicView.get("topTracks")).isEmpty();

        assertThat(api.delete("/api/v1/admin/artists/" + id, null, admin).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.get("/api/v1/artists/" + id, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.delete("/api/v1/admin/artists/" + id, null, admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void writesEmitEntityChangedEventsThroughTheOutbox() {
        UUID artist = catalog.artist("Outbox Band");
        api.patch("/api/v1/admin/artists/" + artist, Map.of("name", "Outbox Band II"), admin);
        api.delete("/api/v1/admin/artists/" + artist, null, admin);

        List<String> actions = jdbc.sql("""
                        SELECT payload -> 'payload' ->> 'action' FROM outbox_event
                        WHERE topic = 'catalog.entity-changed' AND event_key = :id ORDER BY seq""")
                .param("id", artist.toString()).query(String.class).list();
        String snapshotName = jdbc.sql("""
                        SELECT payload -> 'payload' -> 'snapshot' ->> 'name' FROM outbox_event
                        WHERE topic = 'catalog.entity-changed' AND event_key = :id ORDER BY seq OFFSET 1 LIMIT 1""")
                .param("id", artist.toString()).query(String.class).single();

        assertThat(actions).containsExactly("CREATED", "UPDATED", "DELETED");
        assertThat(snapshotName).isEqualTo("Outbox Band II");
    }

    @Test
    void albumAndTrackCreationWithDefaultsAndGenres() {
        UUID artist = catalog.artist("Default Credits");
        ResponseEntity<JsonNode> album = api.post("/api/v1/admin/albums", Map.of("title", "First", "artistId", artist,
                "releaseDate", "2024-05-01", "type", "EP", "genres", List.of("Synthwave", "synthwave ", "Pop")), admin);
        assertThat(album.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(album.getBody().get("genres")).extracting(JsonNode::asText).containsExactly("Pop", "Synthwave");
        assertThat(album.getBody().at("/artist/name").asText()).isEqualTo("Default Credits");
        UUID albumId = UUID.fromString(album.getBody().get("id").asText());

        ResponseEntity<JsonNode> track = api.post("/api/v1/admin/tracks", Map.of("title", "Intro", "albumId", albumId,
                "trackNumber", 1, "isrc", "USRC17607839"), admin);

        assertThat(track.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(track.getBody().get("status").asText()).isEqualTo("DRAFT");
        assertThat(track.getBody().get("discNumber").asInt()).isEqualTo(1);
        assertThat(track.getBody().at("/artists/0/artistId").asText()).isEqualTo(artist.toString());
        assertThat(track.getBody().at("/artists/0/role").asText()).isEqualTo("PRIMARY");
    }

    @Test
    void validationAndReferenceErrors() {
        ResponseEntity<JsonNode> blankName = api.post("/api/v1/admin/artists", Map.of("name", " "), admin);
        ResponseEntity<JsonNode> unknownArtist = api.post("/api/v1/admin/albums", Map.of("title", "X",
                "artistId", UUID.randomUUID(), "releaseDate", "2024-01-01", "type", "ALBUM"), admin);
        ResponseEntity<JsonNode> badType = api.post("/api/v1/admin/albums", Map.of("title", "X",
                "artistId", UUID.randomUUID(), "releaseDate", "2024-01-01", "type", "MIXTAPE"), admin);
        UUID album = catalog.album(catalog.artist("Validator"), "A", LocalDate.of(2020, 1, 1));
        ResponseEntity<JsonNode> badTrack = api.post("/api/v1/admin/tracks", Map.of("title", "T", "albumId", album,
                "trackNumber", 0, "isrc", "nope"), admin);
        ResponseEntity<JsonNode> featuredOnly = api.post("/api/v1/admin/tracks", Map.of("title", "T", "albumId", album,
                "trackNumber", 1, "artists", List.of(Map.of("artistId", catalog.artist("F"), "role", "FEATURED"))), admin);

        assertThat(blankName.getBody().get("code").asText()).isEqualTo("validation-failed");
        assertThat(unknownArtist.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknownArtist.getBody().get("code").asText()).isEqualTo("unknown-artist");
        assertThat(badType.getBody().get("code").asText()).isEqualTo("malformed-request");
        assertThat(badTrack.getBody().get("errors")).extracting(e -> e.get("field").asText())
                .contains("trackNumber", "isrc");
        assertThat(featuredOnly.getBody().get("code").asText()).isEqualTo("primary-artist-required");
    }

    @Test
    void deletesAreRefusedWhileReferenced() {
        UUID artist = catalog.artist("Busy Artist");
        UUID album = catalog.album(artist, "Busy Album", LocalDate.of(2021, 1, 1));
        UUID track = catalog.track(album, "Busy Track", 1);

        ResponseEntity<JsonNode> artistDelete = api.delete("/api/v1/admin/artists/" + artist, null, admin);
        ResponseEntity<JsonNode> albumDelete = api.delete("/api/v1/admin/albums/" + album, null, admin);

        assertThat(artistDelete.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(artistDelete.getBody().get("code").asText()).isEqualTo("artist-in-use");
        assertThat(albumDelete.getBody().get("code").asText()).isEqualTo("album-has-tracks");

        assertThat(api.delete("/api/v1/admin/tracks/" + track, null, admin).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.delete("/api/v1/admin/albums/" + album, null, admin).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.delete("/api/v1/admin/artists/" + artist, null, admin).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void patchTrackAndAlbum() {
        UUID artist = catalog.artist("Patcher");
        UUID album = catalog.album(artist, "Before", LocalDate.of(2019, 1, 1));
        UUID track = catalog.track(album, "Before", 1);

        JsonNode patchedAlbum = api.patch("/api/v1/admin/albums/" + album,
                Map.of("title", "After", "genres", List.of("Jazz")), admin).getBody();
        JsonNode patchedTrack = api.patch("/api/v1/admin/tracks/" + track,
                Map.of("title", "After", "explicit", true, "trackNumber", 2), admin).getBody();

        assertThat(patchedAlbum.get("title").asText()).isEqualTo("After");
        assertThat(patchedAlbum.get("genres")).extracting(JsonNode::asText).containsExactly("Jazz");
        assertThat(patchedTrack.get("title").asText()).isEqualTo("After");
        assertThat(patchedTrack.get("explicit").asBoolean()).isTrue();
        assertThat(patchedTrack.get("trackNumber").asInt()).isEqualTo(2);
        assertThat(api.patch("/api/v1/admin/tracks/" + UUID.randomUUID(), Map.of("title", "x"), admin).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listenersCannotUseAdminEndpoints() {
        String listener = api.register().accessToken();

        assertThat(api.post("/api/v1/admin/artists", Map.of("name", "Nope"), listener).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.get("/api/v1/admin/tracks", listener).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.post("/api/v1/admin/artists", Map.of("name", "Nope"), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void processingDashboardFiltersByStatusAndPaginates() {
        UUID album = catalog.album(catalog.artist("Dash"), "Dash", LocalDate.of(2022, 2, 2));
        List<UUID> created = List.of(catalog.track(album, "D1", 1), catalog.track(album, "D2", 2), catalog.track(album, "D3", 3));
        CatalogFixtures.forceStatus(jdbc, created.get(0), "FAILED");

        List<String> failed = new ArrayList<>();
        api.get("/api/v1/admin/tracks?status=FAILED&limit=100", admin).getBody().get("items")
                .forEach(t -> failed.add(t.get("id").asText()));
        List<String> all = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = api.get("/api/v1/admin/tracks?limit=2" + (cursor == null ? "" : "&cursor=" + cursor), admin).getBody();
            assertThat(page.get("items").size()).isLessThanOrEqualTo(2);
            page.get("items").forEach(t -> all.add(t.get("id").asText()));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
        } while (cursor != null);

        assertThat(failed).contains(created.get(0).toString()).doesNotContain(created.get(1).toString());
        assertThat(all).contains(created.stream().map(UUID::toString).toArray(String[]::new));
        assertThat(new HashSet<>(all)).hasSameSizeAs(all);
        assertThat(api.get("/api/v1/admin/tracks?status=BOGUS", admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
