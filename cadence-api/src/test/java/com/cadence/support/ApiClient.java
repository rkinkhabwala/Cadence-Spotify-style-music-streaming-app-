package com.cadence.support;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** Thin JSON client over the running API used by integration tests. */
public class ApiClient {

    public static final String ADMIN_EMAIL = "admin@cadence.test";
    public static final String ADMIN_PASSWORD = "admin-password-123";
    public static final String PASSWORD = "correct-horse-battery";

    public record Session(UUID userId, String email, String accessToken, String refreshToken) {
    }

    private final TestRestTemplate http;

    public ApiClient(TestRestTemplate http) {
        this.http = http;
    }

    public TestRestTemplate http() {
        return http;
    }

    public ResponseEntity<JsonNode> get(String path, String token) {
        return exchange(HttpMethod.GET, path, null, token, null);
    }

    public ResponseEntity<JsonNode> post(String path, Object body, String token) {
        return exchange(HttpMethod.POST, path, body, token, null);
    }

    public ResponseEntity<JsonNode> put(String path, Object body, String token) {
        return exchange(HttpMethod.PUT, path, body, token, null);
    }

    public ResponseEntity<JsonNode> patch(String path, Object body, String token) {
        return exchange(HttpMethod.PATCH, path, body, token, null);
    }

    public ResponseEntity<JsonNode> delete(String path, Object body, String token) {
        return exchange(HttpMethod.DELETE, path, body, token, null);
    }

    public ResponseEntity<JsonNode> exchange(HttpMethod method, String path, Object body, String token,
                                             HttpHeaders extraHeaders) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        if (extraHeaders != null) {
            headers.addAll(extraHeaders);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    /** Registers a fresh LISTENER with a unique email. */
    public Session register() {
        String email = "user-" + UUID.randomUUID() + "@test.dev";
        ResponseEntity<JsonNode> response = post("/api/v1/auth/register",
                Map.of("email", email, "password", PASSWORD, "displayName", "Test User"), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return session(email, response.getBody());
    }

    public Session admin() {
        return login(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    /** Logs in from a random client IP so tests don't share the per-IP login rate limit. */
    public Session login(String email, String password) {
        ResponseEntity<JsonNode> response = loginFrom(randomIp(), email, password);
        assertThat(response.getStatusCode()).as("login %s: %s", email, response.getBody()).isEqualTo(HttpStatus.OK);
        return session(email, response.getBody());
    }

    public ResponseEntity<JsonNode> loginFrom(String clientIp, String email, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", clientIp);
        return exchange(HttpMethod.POST, "/api/v1/auth/login", Map.of("email", email, "password", password),
                null, headers);
    }

    public static String randomIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255);
    }

    private Session session(String email, JsonNode tokens) {
        String access = tokens.get("accessToken").asText();
        UUID userId = UUID.fromString(get("/api/v1/me", access).getBody().get("id").asText());
        return new Session(userId, email, access, tokens.get("refreshToken").asText());
    }
}
