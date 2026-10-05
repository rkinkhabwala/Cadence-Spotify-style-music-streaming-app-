package com.cadence.catalog;

import com.cadence.IntegrationTest;
import com.cadence.support.CatalogFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogPublicIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    private CatalogFixtures catalog;

    @BeforeEach
    void setUp() {
        catalog = new CatalogFixtures(api, api.admin().accessToken());
    }

    @Test
    void artistShowsTopTenReadyTracksByPlayCount() {
        UUID artist = catalog.artist("Top Ten");
        UUID album = catalog.album(artist, "Hits", LocalDate.of(2023, 1, 1));
        List<UUID> tracks = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            tracks.add(catalog.track(album, "Hit " + i, i));
        }
        for (int i = 0; i < 11; i++) {
            CatalogFixtures.forceReady(jdbc, tracks.get(i), 180_000, i * 10L);
        }
        // tracks.get(11) stays DRAFT

        JsonNode body = api.get("/api/v1/artists/" + artist, null).getBody();

        JsonNode top = body.get("topTracks");
        assertThat(top).hasSize(10);
        assertThat(top.get(0).get("title").asText()).isEqualTo("Hit 11");
        assertThat(top.get(0).get("playCount").asLong()).isEqualTo(100);
        assertThat(top.get(0).at("/album/title").asText()).isEqualTo("Hits");
        assertThat(top.get(0).at("/artists/0/name").asText()).isEqualTo("Top Ten");
        assertThat(top).extracting(t -> t.get("id").asText()).doesNotContain(tracks.get(11).toString(), tracks.get(0).toString());
    }

    @Test
    void artistAlbumsAreNewestFirstWithCursorPagination() {
        UUID artist = catalog.artist("Discography");
        UUID oldest = catalog.album(artist, "Old", LocalDate.of(2001, 1, 1));
        UUID middle = catalog.album(artist, "Mid", LocalDate.of(2011, 1, 1));
        UUID newest = catalog.album(artist, "New", LocalDate.of(2021, 1, 1));

        JsonNode first = api.get("/api/v1/artists/" + artist + "/albums?limit=2", null).getBody();
        JsonNode second = api.get("/api/v1/artists/" + artist + "/albums?limit=2&cursor="
                + first.get("nextCursor").asText(), null).getBody();

        assertThat(first.get("items")).extracting(a -> a.get("id").asText())
                .containsExactly(newest.toString(), middle.toString());
        assertThat(second.get("items")).extracting(a -> a.get("id").asText()).containsExactly(oldest.toString());
        assertThat(second.get("nextCursor").isNull()).isTrue();
        assertThat(api.get("/api/v1/artists/" + UUID.randomUUID() + "/albums", null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.get("/api/v1/artists/" + artist + "/albums?cursor=garbage!", null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void albumListsOnlyReadyTracksInOrder() {
        UUID album = catalog.album(catalog.artist("Tracklist"), "Ordered", LocalDate.of(2020, 6, 1));
        UUID third = catalog.track(album, "Three", 3);
        UUID first = catalog.track(album, "One", 1);
        UUID draft = catalog.track(album, "Two (draft)", 2);
        CatalogFixtures.forceReady(jdbc, third, 200_000, 0);
        CatalogFixtures.forceReady(jdbc, first, 100_000, 0);

        JsonNode body = api.get("/api/v1/albums/" + album, null).getBody();

        assertThat(body.get("tracks")).extracting(t -> t.get("title").asText()).containsExactly("One", "Three");
        assertThat(body.get("totalDurationMs").asLong()).isEqualTo(300_000);
        assertThat(body.get("genres")).extracting(JsonNode::asText).containsExactly("Rock");
        assertThat(body.at("/artist/name").asText()).isEqualTo("Tracklist");
        assertThat(body.get("tracks")).extracting(t -> t.get("id").asText()).doesNotContain(draft.toString());
        assertThat(api.get("/api/v1/albums/" + UUID.randomUUID(), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void trackDetailIsPublicOnlyWhenReady() {
        UUID album = catalog.album(catalog.artist("Detail"), "Detail", LocalDate.of(2020, 6, 1));
        UUID ready = catalog.track(album, "Ready", 1);
        UUID draft = catalog.track(album, "Draft", 2);
        CatalogFixtures.forceReady(jdbc, ready, 123_000, 7);

        JsonNode detail = api.get("/api/v1/tracks/" + ready, null).getBody();

        assertThat(detail.get("durationMs").asInt()).isEqualTo(123_000);
        assertThat(detail.at("/album/id").asText()).isEqualTo(album.toString());
        assertThat(api.get("/api/v1/tracks/" + draft, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.get("/api/v1/tracks/not-a-uuid", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void genresAreListedAlphabetically() {
        catalog.album(catalog.artist("Genres"), "G", LocalDate.of(2020, 1, 1)); // adds "Rock"

        JsonNode body = api.get("/api/v1/genres?limit=100", null).getBody();

        List<String> names = new ArrayList<>();
        body.get("items").forEach(g -> names.add(g.get("name").asText()));
        assertThat(names).contains("Rock").isSorted();
    }
}
