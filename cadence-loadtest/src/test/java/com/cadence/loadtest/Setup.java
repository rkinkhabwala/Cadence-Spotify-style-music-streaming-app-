package com.cadence.loadtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

/** Prepares the data the simulation needs, outside the measured run (plain JDK HttpClient). */
final class Setup {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;

    Setup(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    static String email(int n) {
        return "loadtest-" + n + "@cadence.test";
    }

    static String randomIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255);
    }

    /** READY tracks with a word of their title to search for, via the admin dashboard endpoint. */
    List<String[]> readyTracks(String adminEmail, String adminPassword) {
        if (adminEmail == null || adminPassword == null) {
            throw new IllegalStateException("Set CADENCE_ADMIN_EMAIL and CADENCE_ADMIN_PASSWORD (make loadtest reads .env)");
        }
        String token = login(adminEmail, adminPassword).path("accessToken").asText();
        List<String[]> tracks = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/admin/tracks?status=READY&limit=100"
                    + (cursor == null ? "" : "&cursor=" + cursor))).header("Authorization", "Bearer " + token).GET());
            for (JsonNode track : page.path("items")) {
                String word = track.path("title").asText("music").split("\\s+")[0].toLowerCase(Locale.ROOT);
                tracks.add(new String[] {track.path("id").asText(), word});
            }
            cursor = page.path("nextCursor").isTextual() ? page.path("nextCursor").asText() : null;
        } while (cursor != null);
        return tracks;
    }

    /** Registers the listener accounts that don't exist yet (BCrypt makes this slow, so it isn't measured). */
    void ensureListeners(int count, String password) {
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> work = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String email = email(i);
                work.add(pool.submit(() -> {
                    if (!login(email, password).has("accessToken")) {
                        send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/auth/register"))
                                .header("Content-Type", "application/json").header("X-Forwarded-For", randomIp())
                                .POST(HttpRequest.BodyPublishers.ofString(JSON.createObjectNode().put("email", email)
                                        .put("password", password).put("displayName", "Load listener").toString())));
                    }
                }));
            }
            for (Future<?> f : work) {
                f.get();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not create listener accounts", e);
        }
    }

    private JsonNode login(String email, String password) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/auth/login"))
                .header("Content-Type", "application/json").header("X-Forwarded-For", randomIp())
                .POST(HttpRequest.BodyPublishers.ofString(JSON.createObjectNode().put("email", email)
                        .put("password", password).toString())));
    }

    private JsonNode send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = client.send(request.timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.body() == null || response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("Request failed: " + e, e);
        }
    }
}
