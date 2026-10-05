package com.cadence.streaming;

import com.cadence.IntegrationTest;
import com.cadence.common.storage.ObjectStorage;
import com.cadence.identity.Plan;
import com.cadence.identity.UserAccounts;
import com.cadence.support.ApiClient.Session;
import com.cadence.support.StreamingFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybackIT extends IntegrationTest {

    @Autowired
    ObjectStorage storage;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    UserAccounts accounts;

    private final HttpClient direct = HttpClient.newHttpClient();
    private StreamingFixtures streaming;
    private UUID track;

    @BeforeEach
    void setUp() {
        streaming = new StreamingFixtures(api, api.admin().accessToken(), storage, kafka, objectMapper, jdbc);
        track = streaming.readyTrack("Playable " + UUID.randomUUID());
    }

    @Test
    void freeUserGetsAManifestWithoutThe320Rendition() throws Exception {
        Session free = api.register();

        ResponseEntity<JsonNode> start = api.post("/api/v1/playback/" + track, null, free.accessToken());

        assertThat(start.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = start.getBody();
        assertThat(body.get("durationMs").asInt()).isEqualTo(StreamingFixtures.DURATION_MS);
        assertThat(Instant.parse(body.get("expiresAt").asText()))
                .isBetween(Instant.now().plusSeconds(280), Instant.now().plusSeconds(301));
        String manifestUrl = body.get("manifestUrl").asText();
        assertThat(manifestUrl).startsWith(http.getRootUri() + "/api/v1/playback/" + track + "/master.m3u8?token=");

        HttpResponse<String> master = fetch(manifestUrl); // no Authorization header, like hls.js
        assertThat(master.statusCode()).isEqualTo(200);
        assertThat(master.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> ct.startsWith("application/vnd.apple.mpegurl"));
        assertThat(master.headers().firstValue("Cache-Control")).hasValue("no-store");
        List<String> variants = master.body().lines().filter(l -> !l.startsWith("#")).toList();
        assertThat(variants).hasSize(2).allMatch(v -> v.matches("(96k|160k)/index\\.m3u8\\?token=.+"));

        // variant playlist (resolved relative to the master URL, as hls.js does) has presigned MinIO segment URLs
        URI variantUrl = URI.create(manifestUrl).resolve(variants.get(1));
        HttpResponse<String> media = fetch(variantUrl.toString());
        assertThat(media.statusCode()).isEqualTo(200);
        List<String> segments = media.body().lines().filter(l -> !l.startsWith("#")).toList();
        assertThat(media.body()).contains("#EXT-X-ENDLIST", "#EXTINF:10.000000,");
        assertThat(segments).hasSize(2).allMatch(s -> s.contains("X-Amz-Signature=") && s.contains("/cadence-hls/hls/" + track + "/160k/"));
        long expiresSeconds = Long.parseLong(UriComponentsBuilder.fromUriString(segments.getFirst()).build()
                .getQueryParams().getFirst("X-Amz-Expires"));
        assertThat(expiresSeconds).as("5 min + track duration").isBetween(300L, 313L);

        HttpResponse<byte[]> segment = direct.send(HttpRequest.newBuilder(URI.create(segments.getFirst())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(segment.statusCode()).isEqualTo(200);
        assertThat(segment.body()).isEqualTo(StreamingFixtures.segmentBytes("160k", 0));

        // a free user's media token can't fetch the 320 kbps rendition
        String token = variants.getFirst().substring(variants.getFirst().indexOf("?token=") + 7);
        HttpResponse<String> premiumOnly = fetch(http.getRootUri() + "/api/v1/playback/" + track + "/320k/index.m3u8?token=" + token);
        assertThat(premiumOnly.statusCode()).isEqualTo(403);
        assertThat(premiumOnly.body()).contains("bitrate-not-allowed");
    }

    @Test
    void premiumUserGetsAllThreeRenditions() throws Exception {
        Session premium = api.register();
        accounts.changePlan(premium.userId(), Plan.PREMIUM);

        String manifestUrl = api.post("/api/v1/playback/" + track, null, premium.accessToken()).getBody().get("manifestUrl").asText();
        HttpResponse<String> master = fetch(manifestUrl);
        List<String> variants = master.body().lines().filter(l -> !l.startsWith("#")).toList();

        assertThat(variants).extracting(v -> v.substring(0, v.indexOf('/'))).containsExactly("96k", "160k", "320k");
        assertThat(fetch(URI.create(manifestUrl).resolve(variants.get(2)).toString()).statusCode()).isEqualTo(200);
    }

    @Test
    void onlyReadyTracksArePlayable() {
        String token = api.register().accessToken();
        UUID processing = streaming.processingTrack("Not yet " + UUID.randomUUID());

        ResponseEntity<JsonNode> notReady = api.post("/api/v1/playback/" + processing, null, token);
        ResponseEntity<JsonNode> unknown = api.post("/api/v1/playback/" + UUID.randomUUID(), null, token);
        ResponseEntity<JsonNode> anonymous = api.post("/api/v1/playback/" + track, null, null);

        assertThat(notReady.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(notReady.getBody().get("code").asText()).isEqualTo("track-not-playable");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void manifestUrlsRequireAValidTokenForThatTrackAndScope() throws Exception {
        String access = api.register().accessToken();
        String manifestUrl = api.post("/api/v1/playback/" + track, null, access).getBody().get("manifestUrl").asText();
        String masterToken = manifestUrl.substring(manifestUrl.indexOf("token=") + 6);
        UUID other = streaming.readyTrack("Other " + UUID.randomUUID());
        String base = http.getRootUri() + "/api/v1/playback/";

        HttpResponse<String> tampered = fetch(manifestUrl.substring(0, manifestUrl.length() - 4) + "AAAA");
        HttpResponse<String> otherTrack = fetch(base + other + "/master.m3u8?token=" + masterToken);
        HttpResponse<String> wrongScope = fetch(base + track + "/160k/index.m3u8?token=" + masterToken);
        HttpResponse<String> accessTokenInUrl = fetch(base + track + "/master.m3u8?token=" + access);
        HttpResponse<String> noToken = fetch(base + track + "/master.m3u8");
        ResponseEntity<JsonNode> playbackTokenAsBearer = api.get("/api/v1/me", java.net.URLDecoder.decode(masterToken,
                java.nio.charset.StandardCharsets.UTF_8));

        assertThat(List.of(tampered, otherTrack, wrongScope, accessTokenInUrl)).allSatisfy(r -> {
            assertThat(r.statusCode()).isEqualTo(401);
            assertThat(r.body()).contains("invalid-token");
        });
        assertThat(noToken.statusCode()).isEqualTo(400);
        assertThat(playbackTokenAsBearer.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private HttpResponse<String> fetch(String url) throws Exception {
        return direct.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
