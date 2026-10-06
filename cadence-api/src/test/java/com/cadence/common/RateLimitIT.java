package com.cadence.common;

import com.cadence.IntegrationTest;
import com.cadence.support.ApiClient;
import com.cadence.support.ApiClient.Session;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** The global per-user / per-IP API limit (D97), with a small bucket in its own context. */
class RateLimitIT extends IntegrationTest {

    @DynamicPropertySource
    static void smallBucket(DynamicPropertyRegistry registry) {
        registry.add("cadence.rate-limits.api.capacity", () -> "8");
        registry.add("cadence.rate-limits.api.period", () -> "1m");
    }

    @Test
    void eachUserHasTheirOwnBucketAndTheNinthRequestIs429() {
        Session alice = api.register();   // register + /me lookup: 2 requests from this test client's IP
        Session bob = api.register();
        int ok = 0;
        ResponseEntity<JsonNode> response;
        while ((response = api.get("/api/v1/me", alice.accessToken())).getStatusCode() == HttpStatus.OK) {
            ok++;
        }

        assertThat(ok).as("8 per minute; /me in register already took one").isEqualTo(7);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().get("code").asText()).isEqualTo("rate-limited");
        assertThat(Long.parseLong(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(1L, 60L);
        assertThat(api.get("/api/v1/me", bob.accessToken()).getStatusCode()).as("bob's own bucket").isEqualTo(HttpStatus.OK);
    }

    @Test
    void anonymousCallersAreLimitedByClientIpAndOpsEndpointsAreNotLimited() {
        HttpHeaders ip = new HttpHeaders();
        ip.set("X-Forwarded-For", ApiClient.randomIp());
        int ok = 0;
        while (api.exchange(HttpMethod.GET, "/api/v1/genres", null, null, ip).getStatusCode() == HttpStatus.OK) {
            ok++;
        }
        HttpHeaders otherIp = new HttpHeaders();
        otherIp.set("X-Forwarded-For", ApiClient.randomIp());

        assertThat(ok).isEqualTo(8);
        assertThat(api.exchange(HttpMethod.GET, "/api/v1/genres", null, null, otherIp).getStatusCode()).isEqualTo(HttpStatus.OK);
        for (int i = 0; i < 20; i++) {
            assertThat(api.exchange(HttpMethod.GET, "/actuator/health", null, null, ip).getStatusCode()).isEqualTo(HttpStatus.OK);
            // token-authorized HLS playlists aren't in the per-IP bucket (a whole NAT of listeners shares it): an
            // invalid token is answered by the endpoint (401), never by the limiter (429)
            assertThat(api.exchange(HttpMethod.GET, "/api/v1/playback/" + java.util.UUID.randomUUID()
                    + "/master.m3u8?token=x", null, null, ip).getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(api.exchange(HttpMethod.GET, "/api/v1/playback/" + java.util.UUID.randomUUID()
                    + "/160k/index.m3u8?token=x", null, null, ip).getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
    }
}
