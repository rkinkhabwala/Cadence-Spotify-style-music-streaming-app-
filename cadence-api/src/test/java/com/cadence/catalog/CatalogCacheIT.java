package com.cadence.catalog;

import com.cadence.IntegrationTest;
import com.cadence.support.CatalogFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 7: artist and album pages are cached in Redis (TTL 10 min) and evicted on change. */
class CatalogCacheIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;
    @Autowired
    StringRedisTemplate redis;

    private String adminToken;
    private CatalogFixtures catalog;

    @BeforeEach
    void setUp() {
        adminToken = api.admin().accessToken();
        catalog = new CatalogFixtures(api, adminToken);
    }

    @Test
    void artistPageIsServedFromRedisUntilTheCatalogChanges() {
        UUID artist = catalog.artist("Cached Artist");
        assertThat(api.get("/api/v1/artists/" + artist, null).getBody().get("bio").isNull()).isTrue();
        String key = "cadence:catalog.artist::" + artist;
        assertThat(redis.hasKey(key)).isTrue();
        assertThat(redis.getExpire(key)).isBetween(1L, 600L);

        // a change behind the API's back is invisible while the page is cached ...
        jdbc.sql("UPDATE artists SET bio = 'sneaky' WHERE id = :id").param("id", artist).update();
        assertThat(api.get("/api/v1/artists/" + artist, null).getBody().get("bio").isNull()).isTrue();

        // ... and any catalog write evicts it
        assertThat(api.patch("/api/v1/admin/artists/" + artist, Map.of("name", "Renamed Cached Artist"), adminToken)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        var fresh = api.get("/api/v1/artists/" + artist, null).getBody();
        assertThat(fresh.get("name").asText()).isEqualTo("Renamed Cached Artist");
        assertThat(fresh.get("bio").asText()).isEqualTo("sneaky");
    }

    @Test
    void albumPageIsEvictedWhenATrackOrTheArtistChanges() {
        UUID artist = catalog.artist("Album Cache Artist");
        UUID album = catalog.album(artist, "Album Cache", LocalDate.of(2022, 3, 3));
        UUID track = catalog.track(album, "Only Track", 1);
        CatalogFixtures.forceReady(jdbc, track, 60_000, 0);
        assertThat(api.get("/api/v1/albums/" + album, null).getBody().get("tracks")).hasSize(1);
        assertThat(redis.hasKey("cadence:catalog.album::" + album)).isTrue();

        api.patch("/api/v1/admin/tracks/" + track, Map.of("title", "Renamed Track"), adminToken);
        assertThat(api.get("/api/v1/albums/" + album, null).getBody().at("/tracks/0/title").asText()).isEqualTo("Renamed Track");

        api.patch("/api/v1/admin/artists/" + artist, Map.of("name", "Renamed Album Cache Artist"), adminToken);
        assertThat(api.get("/api/v1/albums/" + album, null).getBody().at("/artist/name").asText())
                .isEqualTo("Renamed Album Cache Artist");
    }

    @Test
    void missingEntitiesAreNotCached() {
        UUID unknown = UUID.randomUUID();
        assertThat(api.get("/api/v1/artists/" + unknown, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(redis.hasKey("cadence:catalog.artist::" + unknown)).isFalse();
    }
}
