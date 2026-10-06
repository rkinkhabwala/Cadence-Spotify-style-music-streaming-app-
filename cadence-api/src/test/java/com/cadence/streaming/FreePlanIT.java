package com.cadence.streaming;

import com.cadence.IntegrationTest;
import com.cadence.identity.Plan;
import com.cadence.identity.UserAccounts;
import com.cadence.support.ApiClient.Session;
import com.cadence.support.CatalogFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Free vs Premium rules (spec 4, spec 9 Phase 3 AC4; D96). The 320 kbps cap is covered by PlaybackIT. */
class FreePlanIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;
    @Autowired
    UserAccounts accounts;
    @Autowired
    StringRedisTemplate redis;

    private UUID track;
    private Session free;

    @BeforeEach
    void setUp() {
        CatalogFixtures catalog = new CatalogFixtures(api, api.admin().accessToken());
        track = catalog.track(catalog.album(catalog.artist("Skip Artist"), "Skip Album", LocalDate.of(2024, 1, 1)),
                "Skippable", 1);
        CatalogFixtures.forceReady(jdbc, track, 200_000, 0);
        free = api.register();
    }

    /** Spec 9 Phase 3 AC4: a 7th skip within an hour returns 429. */
    @Test
    void aFreeUsersSeventhSkipWithinAnHourIs429() {
        UUID third = null;
        for (int i = 1; i <= 6; i++) {
            UUID playId = UUID.randomUUID();
            third = i == 3 ? playId : third;
            ResponseEntity<JsonNode> ok = skip(free, playId);
            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(ok.getBody().get("remaining").asInt()).isEqualTo(6 - i);
            assertThat(ok.getBody().get("limit").asInt()).isEqualTo(6);
        }

        ResponseEntity<JsonNode> seventh = skip(free, UUID.randomUUID());

        assertThat(seventh.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(seventh.getBody().get("code").asText()).isEqualTo("skip-limit-reached");
        assertThat(Long.parseLong(seventh.getHeaders().getFirst("Retry-After"))).isBetween(3_500L, 3_600L);
        assertThat(skip(free, third).getStatusCode()).as("a retried skip doesn't count twice").isEqualTo(HttpStatus.OK);
        assertThat(skip(free, null).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void theWindowRollsSkipsOlderThanAnHourNoLongerCount() {
        long now = Instant.now().toEpochMilli();
        String key = "cadence:skips:" + free.userId();
        for (int i = 0; i < 5; i++) {
            redis.opsForZSet().add(key, "old-" + i, now - 61 * 60_000L);   // outside the window
        }
        redis.opsForZSet().add(key, "recent-1", now - 59 * 60_000L);       // still inside: expires in ~1 min
        for (int i = 0; i < 5; i++) {
            assertThat(skip(free, UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<JsonNode> refused = skip(free, UUID.randomUUID());

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Long.parseLong(refused.getHeaders().getFirst("Retry-After"))).as("when recent-1 leaves the window")
                .isBetween(50L, 61L);
    }

    @Test
    void premiumSkipsAreUnlimited() {
        Session premium = api.register();
        accounts.changePlan(premium.userId(), Plan.PREMIUM);

        for (int i = 0; i < 10; i++) {
            ResponseEntity<JsonNode> ok = skip(premium, UUID.randomUUID());
            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(ok.getBody().get("remaining").isNull()).isTrue();
        }
        assertThat(api.post("/api/v1/playback/" + track + "/skip", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void everyThirdPlaybackOfAFreeUserCarriesAnAdSlotPremiumNone() {
        for (int i = 1; i <= 6; i++) {
            JsonNode start = start(free);
            if (i % 3 == 0) {
                assertThat(start.get("adSlot").get("type").asText()).isEqualTo("placeholder");
                assertThat(start.get("adSlot").get("durationMs").asLong()).isEqualTo(5_000);
            } else {
                assertThat(start.has("adSlot")).as("start %d", i).isFalse();
            }
        }
        Session premium = api.register();
        accounts.changePlan(premium.userId(), Plan.PREMIUM);
        for (int i = 0; i < 6; i++) {
            assertThat(start(premium).has("adSlot")).isFalse();
        }
    }

    private ResponseEntity<JsonNode> skip(Session who, UUID playId) {
        return api.post("/api/v1/playback/" + track + "/skip", playId == null ? null : Map.of("playId", playId),
                who.accessToken());
    }

    private JsonNode start(Session who) {
        ResponseEntity<JsonNode> response = api.post("/api/v1/playback/" + track, null, who.accessToken());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
