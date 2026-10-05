package com.cadence.e2e;

import com.cadence.events.EventEnvelope;
import com.cadence.events.Topics;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Spec 9, Phase 1 acceptance criteria, exercised against the real packaged applications. Methods run in order and
 * share state (the uploaded tracks), like a user journey.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase1AcceptanceIT {

    static CadenceStack stack;
    static Http http;
    static String adminToken;
    static String listenerToken;
    static UUID listenerId;
    static final List<UUID> readyTracks = new ArrayList<>();

    @BeforeAll
    static void startStack() throws Exception {
        assertThat(Media.run("ffmpeg", "-version").exitCode()).as("ffmpeg must be installed").isZero();
        stack = CadenceStack.start();
        http = new Http(stack.apiUrl());
    }

    @AfterAll
    static void stopStack() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("AC1: register, log in, get tokens; expired access token + valid refresh token yields a new pair")
    void registerLoginAndRefreshAnExpiredSession() throws Exception {
        String email = "listener-" + UUID.randomUUID() + "@e2e.test";
        Http.Response registered = http.post("/api/v1/auth/register",
                Map.of("email", email, "password", "listener-password", "displayName", "Listener"), null);
        assertThat(registered.status()).isEqualTo(201);
        Http.Response login = http.post("/api/v1/auth/login", Map.of("email", email, "password", "listener-password"), null);
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.body().get("accessToken").asText()).isNotBlank();
        assertThat(login.body().get("expiresIn").asInt()).isEqualTo(900);
        listenerId = UUID.fromString(http.get("/api/v1/me", login.body().get("accessToken").asText()).body().get("id").asText());

        String expired = expiredAccessToken(listenerId);
        assertThat(http.get("/api/v1/me", expired).status()).isEqualTo(401);
        String refreshCookie = refreshCookie(login);
        assertThat(login.body().has("refreshToken")).as("refresh token only travels in the HttpOnly cookie").isFalse();
        Http.Response refreshed = http.send("POST", "/api/v1/auth/refresh", null, null,
                "Cookie", "cadence_refresh=" + refreshCookie, "X-Cadence-CSRF", "1");

        assertThat(refreshed.status()).isEqualTo(200);
        assertThat(refreshCookie(refreshed)).isNotBlank().isNotEqualTo(refreshCookie);
        listenerToken = refreshed.body().get("accessToken").asText();
        assertThat(http.get("/api/v1/me", listenerToken).body().get("email").asText()).isEqualTo(email);
    }

    @Test
    @Order(2)
    @DisplayName("AC3: a LISTENER calling /admin/** gets 403")
    void listenerIsForbiddenOnAdminEndpoints() {
        assertThat(http.post("/api/v1/admin/artists", Map.of("name", "Nope"), listenerToken).status()).isEqualTo(403);
        assertThat(http.get("/api/v1/admin/tracks", listenerToken).status()).isEqualTo(403);
        assertThat(http.post("/api/v1/admin/tracks/" + UUID.randomUUID() + "/upload-url",
                Map.of("extension", "mp3", "sizeBytes", 10), listenerToken).status()).isEqualTo(403);
        assertThat(http.delete("/api/v1/admin/albums/" + UUID.randomUUID(), listenerToken).status()).isEqualTo(403);
    }

    @Test
    @Order(3)
    @DisplayName("AC2: an admin uploads an MP3; within 60 s it is READY with three HLS renditions in MinIO")
    void adminUploadsAnMp3AndItBecomesReadyWithinSixtySeconds() throws Exception {
        adminToken = http.post("/api/v1/auth/login",
                Map.of("email", CadenceStack.ADMIN_EMAIL, "password", CadenceStack.ADMIN_PASSWORD), null).body().get("accessToken").asText();
        UUID artist = id(http.post("/api/v1/admin/artists", Map.of("name", "Acceptance Artist"), adminToken));
        UUID album = id(http.post("/api/v1/admin/albums", Map.of("title", "Acceptance", "artistId", artist,
                "releaseDate", "2026-10-05", "type", "ALBUM", "genres", List.of("Electronic")), adminToken));

        UUID main = uploadAndAwaitReady(album, "Thirty Seconds", 1, Media.toneMp3(30, 440), Duration.ofSeconds(60));
        readyTracks.add(main);
        readyTracks.add(uploadAndAwaitReady(album, "Second", 2, Media.toneMp3(4, 550), Duration.ofSeconds(60)));
        readyTracks.add(uploadAndAwaitReady(album, "Third", 3, Media.toneMp3(4, 660), Duration.ofSeconds(60)));

        List<String> keys = new ArrayList<>();
        try (var s3 = stack.s3()) {
            s3.listObjectsV2Paginator(l -> l.bucket("cadence-hls").prefix("hls/" + main + "/"))
                    .contents().forEach(o -> keys.add(o.key()));
        }
        for (String rendition : List.of("96k", "160k", "320k")) {
            assertThat(keys).contains("hls/" + main + "/" + rendition + "/index.m3u8");
            // 30 s in 10 s segments (+ a few ms of MP3 encoder padding may add a tiny last segment)
            assertThat(keys.stream().filter(k -> k.startsWith("hls/" + main + "/" + rendition + "/segment_"))).hasSizeBetween(3, 4);
        }
        assertThat(keys).contains("hls/" + main + "/master.m3u8");
        JsonNode track = http.get("/api/v1/tracks/" + main, null).body();
        assertThat(track.get("durationMs").asInt()).isBetween(29_900, 30_100);
    }

    @Test
    @Order(4)
    @DisplayName("AC4: POST /playback returns a manifest URL that plays and seeks (FFmpeg as the HLS client)")
    void playbackManifestPlaysAndSeeks() throws Exception {
        UUID track = readyTracks.getFirst();
        Http.Response start = http.post("/api/v1/playback/" + track, null, listenerToken);
        assertThat(start.status()).isEqualTo(200);
        String manifestUrl = start.body().get("manifestUrl").asText();
        assertThat(start.body().get("durationMs").asInt()).isBetween(29_900, 30_100);

        Media.Result probe = Media.probe(manifestUrl);
        assertThat(probe.exitCode()).as(probe.output()).isZero();
        assertThat(probe.output().lines().filter(l -> l.startsWith("program")).count())
                .as("free listener sees 96 and 160 kbps only").isEqualTo(2);
        Media.Result fromStart = Media.playFrom(manifestUrl, 0, 3);
        Media.Result seekedIntoThirdSegment = Media.playFrom(manifestUrl, 22, 5);
        assertThat(fromStart.exitCode()).as(fromStart.output()).isZero();
        assertThat(seekedIntoThirdSegment.exitCode()).as(seekedIntoThirdSegment.output()).isZero();

        HttpResponse<byte[]> range = http.getBytes("/api/v1/tracks/" + track + "/stream", listenerToken, "bytes=0-1023");
        assertThat(range.statusCode()).isEqualTo(206);
        assertThat(range.body()).hasSize(1024);
        assertThat(http.getBytes("/api/v1/tracks/" + track + "/stream", listenerToken, "bytes=999999999-").statusCode())
                .isEqualTo(416);
        assertThat(Media.run("curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                stack.apiUrl() + "/dev/player.html").output()).as("hls.js test page is served (dev profile)").isEqualTo("200");
    }

    @Test
    @Order(5)
    @DisplayName("AC5: create a playlist, add 3 tracks, reorder them, and see the new order persisted")
    void playlistOrderIsPersisted() {
        Http.Response created = http.post("/api/v1/playlists", Map.of("name", "Acceptance mix"), listenerToken);
        assertThat(created.status()).isEqualTo(201);
        UUID playlist = id(created);
        UUID a = readyTracks.get(0), b = readyTracks.get(1), c = readyTracks.get(2);

        assertThat(http.post("/api/v1/playlists/" + playlist + "/tracks", Map.of("trackIds", List.of(a, b, c)), listenerToken)
                .body().get("trackCount").asInt()).isEqualTo(3);
        java.util.HashMap<String, Object> toTop = new java.util.HashMap<>();
        toTop.put("trackId", c);
        toTop.put("afterTrackId", null);
        assertThat(http.put("/api/v1/playlists/" + playlist + "/tracks/reorder", toTop, listenerToken).status()).isEqualTo(200);
        assertThat(http.put("/api/v1/playlists/" + playlist + "/tracks/reorder", Map.of("trackId", a, "afterTrackId", b),
                listenerToken).status()).isEqualTo(200);

        List<String> order = new ArrayList<>();
        http.get("/api/v1/playlists/" + playlist, listenerToken).body().at("/tracks/items")
                .forEach(i -> order.add(i.get("trackId").asText()));
        assertThat(order).containsExactly(c.toString(), b.toString(), a.toString());
    }

    @Test
    @Order(6)
    @DisplayName("AC6: like/unlike and follow/unfollow are idempotent")
    void likesAndFollowsAreIdempotent() {
        UUID track = readyTracks.getFirst();
        UUID artist = UUID.fromString(http.get("/api/v1/tracks/" + track, null).body().at("/artists/0/id").asText());
        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(Topics.LIBRARY_TRACK_LIKED));

            for (int i = 0; i < 2; i++) {
                assertThat(http.put("/api/v1/me/likes/tracks/" + track, null, listenerToken).status()).isEqualTo(204);
                assertThat(http.put("/api/v1/me/following/artists/" + artist, null, listenerToken).status()).isEqualTo(204);
            }
            assertThat(http.get("/api/v1/me/likes/tracks", listenerToken).body().get("items")).hasSize(1);
            for (int i = 0; i < 2; i++) {
                assertThat(http.send("DELETE", "/api/v1/me/likes/tracks/" + track, null, listenerToken).status()).isEqualTo(204);
                assertThat(http.send("DELETE", "/api/v1/me/following/artists/" + artist, null, listenerToken).status()).isEqualTo(204);
            }
            assertThat(http.get("/api/v1/me/likes/tracks", listenerToken).body().get("items")).isEmpty();

            List<String> types = new ArrayList<>();
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                consumer.poll(Duration.ofMillis(250)).forEach(r -> {
                    EventEnvelope e = EventEnvelope.fromJson(r.value(), Http.JSON);
                    if (listenerId.equals(e.userId())) {
                        types.add(e.eventType());
                    }
                });
                return types.size() >= 2;
            });
            assertThat(types).as("exactly one event per state change, in the shared envelope")
                    .containsExactly("track-liked", "track-unliked");
        }
    }

    private UUID uploadAndAwaitReady(UUID album, String title, int number, byte[] mp3, Duration within) throws Exception {
        UUID track = id(http.post("/api/v1/admin/tracks", Map.of("title", title, "albumId", album, "trackNumber", number), adminToken));
        JsonNode url = http.post("/api/v1/admin/tracks/" + track + "/upload-url",
                Map.of("extension", "mp3", "sizeBytes", mp3.length), adminToken).body();
        assertThat(http.putBytes(url.get("uploadUrl").asText(), mp3, "audio/mpeg").statusCode()).isEqualTo(200);
        Instant completed = Instant.now();
        assertThat(http.post("/api/v1/admin/tracks/" + track + "/upload-complete", null, adminToken).status()).isEqualTo(202);
        await().atMost(within).pollInterval(Duration.ofMillis(250)).until(() ->
                "READY".equals(http.get("/api/v1/admin/tracks/" + track, adminToken).body().get("status").asText()));
        System.out.printf("[acceptance] %s READY %d ms after upload-complete%n", title,
                Duration.between(completed, Instant.now()).toMillis());
        return track;
    }

    private static KafkaConsumer<String, String> consumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, stack.kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "e2e-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }

    /** Signed with the stack's real key but expired 5 minutes ago. */
    private static String expiredAccessToken(UUID userId) throws Exception {
        Instant issued = Instant.now().minus(Duration.ofMinutes(20));
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder()
                .issuer("cadence").subject(userId.toString()).issueTime(Date.from(issued))
                .expirationTime(Date.from(issued.plus(Duration.ofMinutes(15))))
                .claim("token_use", "access").claim("roles", List.of("LISTENER")).build());
        jwt.sign(new RSASSASigner(stack.signingKeys.getPrivate()));
        return jwt.serialize();
    }

    private static UUID id(Http.Response response) {
        assertThat(response.status()).as("%s", response.body()).isIn(200, 201);
        return UUID.fromString(response.body().get("id").asText());
    }

    private static String refreshCookie(Http.Response response) {
        return response.raw().headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("cadence_refresh="))
                .map(c -> c.substring("cadence_refresh=".length(), c.indexOf(';')))
                .findFirst().orElseThrow();
    }
}
