package com.cadence.library;

import com.cadence.IntegrationTest;
import com.cadence.support.ApiClient.Session;
import com.cadence.support.CatalogFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    private CatalogFixtures catalog;
    private UUID artist;
    private UUID album;
    private Session user;

    @BeforeEach
    void setUp() {
        catalog = new CatalogFixtures(api, api.admin().accessToken());
        artist = catalog.artist("Library Artist");
        album = catalog.album(artist, "Library Album", LocalDate.of(2023, 7, 7));
        user = api.register();
    }

    @Test
    void likeAndUnlikeAreIdempotentAndPublishOneEventPerChange() {
        UUID track = ready("Liked");
        String path = "/api/v1/me/likes/tracks/" + track;

        assertThat(api.put(path, null, user.accessToken()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.put(path, null, user.accessToken()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        JsonNode likes = api.get("/api/v1/me/likes/tracks", user.accessToken()).getBody();
        assertThat(likes.get("items")).hasSize(1);
        assertThat(likes.at("/items/0/track/title").asText()).isEqualTo("Liked");

        assertThat(api.delete(path, null, user.accessToken()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.delete(path, null, user.accessToken()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.get("/api/v1/me/likes/tracks", user.accessToken()).getBody().get("items")).isEmpty();

        assertThat(events("library.track-liked", user.userId())).containsExactly("track-liked:song:" + track,
                "track-unliked:song:" + track);
    }

    @Test
    void likesAreListedMostRecentFirstWithPagination() {
        UUID a = ready("A"), b = ready("B"), c = ready("C");
        for (UUID t : List.of(a, b, c)) {
            api.put("/api/v1/me/likes/tracks/" + t, null, user.accessToken());
        }

        JsonNode page1 = api.get("/api/v1/me/likes/tracks?limit=2", user.accessToken()).getBody();
        JsonNode page2 = api.get("/api/v1/me/likes/tracks?limit=2&cursor=" + page1.get("nextCursor").asText(),
                user.accessToken()).getBody();

        assertThat(page1.get("items")).extracting(i -> i.at("/track/id").asText()).containsExactly(c.toString(), b.toString());
        assertThat(page2.get("items")).extracting(i -> i.at("/track/id").asText()).containsExactly(a.toString());
    }

    @Test
    void onlyExistingPlayableTracksCanBeLiked() {
        UUID draft = catalog.track(album, "Not ready", 2);

        assertThat(api.put("/api/v1/me/likes/tracks/" + UUID.randomUUID(), null, user.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.put("/api/v1/me/likes/tracks/" + draft, null, user.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.put("/api/v1/me/likes/tracks/" + draft, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void followAndUnfollowAreIdempotentAndPublishArtistEvents() {
        String path = "/api/v1/me/following/artists/" + artist;

        assertThat(api.put(path, null, user.accessToken()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.put(path, null, user.accessToken()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.delete(path, null, user.accessToken()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.delete(path, null, user.accessToken()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(events("library.artist-followed", user.userId())).containsExactly("artist-followed:artist:" + artist,
                "artist-unfollowed:artist:" + artist);
        assertThat(jdbc.sql("SELECT count(*) FROM followed_artists WHERE user_id = :u").param("u", user.userId())
                .query(Long.class).single()).isZero();
        assertThat(api.put("/api/v1/me/following/artists/" + UUID.randomUUID(), null, user.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void savedAlbums() {
        String path = "/api/v1/me/albums/" + album;

        api.put(path, null, user.accessToken());
        api.put(path, null, user.accessToken());
        JsonNode saved = api.get("/api/v1/me/albums", user.accessToken()).getBody();
        api.delete(path, null, user.accessToken());

        assertThat(saved.get("items")).hasSize(1);
        assertThat(saved.at("/items/0/album/title").asText()).isEqualTo("Library Album");
        assertThat(api.get("/api/v1/me/albums", user.accessToken()).getBody().get("items")).isEmpty();
        assertThat(api.put("/api/v1/me/albums/" + UUID.randomUUID(), null, user.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private UUID ready(String title) {
        UUID track = catalog.track(album, title, 1);
        CatalogFixtures.forceReady(jdbc, track, 180_000, 0);
        return track;
    }

    /** eventType:itemType:itemId of the user's library events, in outbox order, after checking the envelope. */
    private List<String> events(String topic, UUID userId) {
        return jdbc.sql("""
                        SELECT payload ->> 'eventType' || ':' || (payload ->> 'itemType') || ':' || (payload ->> 'itemId')
                        FROM outbox_event WHERE topic = :t AND event_key = :k AND payload ->> 'userId' = :k ORDER BY seq""")
                .param("t", topic).param("k", userId.toString()).query(String.class).list();
    }
}
