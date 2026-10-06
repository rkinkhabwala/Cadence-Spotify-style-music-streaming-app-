package com.cadence.loadtest;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.exitBlockOnFail;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.percent;
import static io.gatling.javaapi.core.CoreDsl.randomSwitch;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.regex;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * Spec 9 Phase 3: "load test with Gatling (target: 500 concurrent listeners locally)", "under 1 % errors and p95
 * playback-start under 200 ms" (D99). Runs against a running, seeded stack: {@code make loadtest}.
 *
 * <p>Each virtual listener logs in, opens home, then {@value #PLAYS_PER_SESSION} times: types a search (suggest +
 * search), starts playback ({@code POST /playback}, the measured "playback start"), loads the master and variant
 * playlists and the first segment like hls.js, reports the play at 30 s, and sometimes likes the track; with think
 * times in between. The closed injection model keeps {@code LOADTEST_USERS} listeners active at all times.
 *
 * <p>Every listener sends its own random {@code X-Forwarded-For} (the API trusts it from the private Docker network,
 * D23), so the per-IP login limit of 5/min applies per simulated listener, as it would to real clients.
 */
public class ListenerSimulation extends Simulation {

    static final String BASE_URL = env("LOADTEST_BASE_URL", "http://localhost:8080");
    static final int USERS = Integer.parseInt(env("LOADTEST_USERS", "500"));
    static final Duration RAMP = Duration.ofSeconds(Long.parseLong(env("LOADTEST_RAMP_SECONDS", "60")));
    static final Duration STEADY = Duration.ofSeconds(Long.parseLong(env("LOADTEST_STEADY_SECONDS", "180")));
    static final int PLAYS_PER_SESSION = 5;
    static final String PASSWORD = "loadtest-password";

    private final List<String[]> tracks = new ArrayList<>();   // {id, search word}

    @Override
    public void before() {
        Setup setup = new Setup(BASE_URL);
        tracks.addAll(setup.readyTracks(env("CADENCE_ADMIN_EMAIL", null), env("CADENCE_ADMIN_PASSWORD", null)));
        if (tracks.isEmpty()) {
            throw new IllegalStateException("No READY tracks: run `make seed` first");
        }
        setup.ensureListeners(USERS, PASSWORD);
        System.out.printf("[loadtest] %d READY tracks, %d listeners, %s ramp + %s steady against %s%n",
                tracks.size(), USERS, RAMP, STEADY, BASE_URL);
    }

    HttpProtocolBuilder protocol = http.baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("cadence-loadtest");

    ChainBuilder pickTrack = exec(session -> {
        String[] track = tracks.get(ThreadLocalRandom.current().nextInt(tracks.size()));
        return session.set("trackId", track[0]).set("q", track[1]).set("playId", UUID.randomUUID().toString());
    });

    /** A failed step ends that playback (as a real player would give up), so one error isn't counted three times. */
    ChainBuilder listen = exitBlockOnFail().on(
            exec(http("search suggest").get("/api/v1/search/suggest").queryParam("q", "#{q}")
                    .header("Authorization", "Bearer #{token}")),
            exec(http("search").get("/api/v1/search").queryParam("q", "#{q}").queryParam("types", "track")
                    .header("Authorization", "Bearer #{token}")),
            exec(http("playback start").post("/api/v1/playback/#{trackId}").header("Authorization", "Bearer #{token}")
                    .check(status().is(200), jsonPath("$.manifestUrl").saveAs("master"))),
            exec(http("master playlist").get("#{master}")
                    .check(regex("(\\d+k/index\\.m3u8\\?token=\\S+)").saveAs("variant"))),
            exec(session -> session.set("variantUrl",
                    URI.create(session.getString("master")).resolve(session.getString("variant")).toString())),
            exec(http("variant playlist").get("#{variantUrl}").check(regex("(?m)^(http\\S+)$").saveAs("segment"))),
            exec(http("first segment").get("#{segment}").header("Accept", "*/*")),
            pause(Duration.ofSeconds(2), Duration.ofSeconds(5)),
            exec(http("play report").post("/api/v1/activity/plays").header("Authorization", "Bearer #{token}")
                    .body(StringBody("""
                            {"playId":"#{playId}","trackId":"#{trackId}","msPlayed":30000,"source":"SEARCH",\
                            "sessionId":"loadtest-#{ip}"}"""))
                    .check(status().is(200))),
            randomSwitch().on(percent(30.0).then(
                    exec(http("like").put("/api/v1/me/likes/tracks/#{trackId}").header("Authorization", "Bearer #{token}")
                            .check(status().is(204))))),
            pause(Duration.ofSeconds(3), Duration.ofSeconds(8)));

    ScenarioBuilder listener = scenario("listener")
            .exec(session -> session
                    .set("email", Setup.email(ThreadLocalRandom.current().nextInt(USERS)))
                    .set("ip", Setup.randomIp()))
            .exec(http("login").post("/api/v1/auth/login").header("X-Forwarded-For", "#{ip}")
                    .body(StringBody("{\"email\":\"#{email}\",\"password\":\"" + PASSWORD + "\"}"))
                    .check(status().is(200), jsonPath("$.accessToken").saveAs("token")))
            .exec(http("home").get("/api/v1/home").header("Authorization", "Bearer #{token}"))
            .repeat(PLAYS_PER_SESSION).on(pickTrack, listen);

    {
        setUp(listener.injectClosed(rampConcurrentUsers(0).to(USERS).during(RAMP),
                constantConcurrentUsers(USERS).during(STEADY)))
                .protocols(protocol)
                .assertions(
                        global().failedRequests().percent().lt(1.0),
                        details("playback start").responseTime().percentile(95.0).lt(200));
    }

    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
