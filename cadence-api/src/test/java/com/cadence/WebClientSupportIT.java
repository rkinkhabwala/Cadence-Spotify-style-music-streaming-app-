package com.cadence;

import com.cadence.support.ApiClient.Session;
import com.cadence.support.CatalogFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** API features the web client relies on (slice 2.3): CORS, the followed-artists list, playlist owner names. */
class WebClientSupportIT extends IntegrationTest {

    private static final String WEB = "http://localhost:5173";

    @Test
    void corsAllowsTheWebClientOriginOnly() {
        HttpHeaders preflight = new HttpHeaders();
        preflight.setOrigin(WEB);
        preflight.setAccessControlRequestMethod(HttpMethod.PATCH);
        preflight.setAccessControlRequestHeaders(java.util.List.of("authorization", "content-type", "if-match"));
        ResponseEntity<String> allowed = cors("/api/v1/playlists/" + UUID.randomUUID(), HttpMethod.OPTIONS, preflight);
        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(allowed.getHeaders().getAccessControlAllowOrigin()).isEqualTo(WEB);
        assertThat(allowed.getHeaders().getAccessControlAllowMethods()).contains(HttpMethod.PATCH);

        HttpHeaders evil = new HttpHeaders();
        evil.setOrigin("https://evil.example");
        evil.setAccessControlRequestMethod(HttpMethod.GET);
        assertThat(cors("/api/v1/me", HttpMethod.OPTIONS, evil).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        HttpHeaders simple = new HttpHeaders();
        simple.setOrigin(WEB);
        ResponseEntity<String> get = cors("/api/v1/genres", HttpMethod.GET, simple);
        assertThat(get.getHeaders().getAccessControlAllowOrigin()).isEqualTo(WEB);
        assertThat(get.getHeaders().getAccessControlExposeHeaders()).contains(HttpHeaders.ETAG, HttpHeaders.RETRY_AFTER);
    }

    @Test
    void followedArtistsAreListedMostRecentFirstWithPaging() {
        CatalogFixtures catalog = new CatalogFixtures(api, api.admin().accessToken());
        UUID first = catalog.artist("Followed First"), second = catalog.artist("Followed Second"), third = catalog.artist("Followed Third");
        Session user = api.register();
        for (UUID artist : java.util.List.of(first, second, third)) {
            api.put("/api/v1/me/following/artists/" + artist, null, user.accessToken());
        }
        api.put("/api/v1/me/following/artists/" + second, null, user.accessToken());   // idempotent: order unchanged

        JsonNode page1 = api.get("/api/v1/me/following/artists?limit=2", user.accessToken()).getBody();
        JsonNode page2 = api.get("/api/v1/me/following/artists?limit=2&cursor=" + page1.get("nextCursor").asText(),
                user.accessToken()).getBody();

        assertThat(page1.get("items")).extracting(i -> i.at("/artist/name").asText()).containsExactly("Followed Third", "Followed Second");
        assertThat(page2.get("items")).extracting(i -> i.at("/artist/id").asText()).containsExactly(first.toString());
        assertThat(page2.get("nextCursor").isNull()).isTrue();
        assertThat(page1.at("/items/0/followedAt").asText()).isNotBlank();
        assertThat(api.get("/api/v1/me/following/artists", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(api.get("/api/v1/me/following/artists?cursor=bad!", user.accessToken()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void playlistDetailCarriesTheOwnersDisplayName() {
        Session owner = api.register();
        api.patch("/api/v1/me", Map.of("displayName", "Mix Master"), owner.accessToken());
        String id = api.post("/api/v1/playlists", Map.of("name", "Shared", "visibility", "PUBLIC"), owner.accessToken())
                .getBody().get("id").asText();

        JsonNode seenByOther = api.get("/api/v1/playlists/" + id, api.register().accessToken()).getBody();

        assertThat(seenByOther.get("ownerId").asText()).isEqualTo(owner.userId().toString());
        assertThat(seenByOther.get("ownerName").asText()).isEqualTo("Mix Master");
    }

    private ResponseEntity<String> cors(String path, HttpMethod method, HttpHeaders headers) {
        return api.http().exchange(path, method, new org.springframework.http.HttpEntity<>(headers), String.class);
    }
}
