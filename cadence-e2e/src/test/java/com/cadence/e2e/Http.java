package com.cadence.e2e;

import com.cadence.events.CadenceJackson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Minimal JSON-over-HTTP client for the acceptance tests (JDK HttpClient: no hidden retries). */
final class Http {

    record Response(int status, JsonNode body, HttpResponse<String> raw) {
        String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }
    }

    static final ObjectMapper JSON = CadenceJackson.newObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;

    Http(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    Response get(String path, String token) {
        return send("GET", path, null, token);
    }

    Response post(String path, Object body, String token) {
        return send("POST", path, body, token);
    }

    Response put(String path, Object body, String token) {
        return send("PUT", path, body, token);
    }

    Response delete(String path, String token) {
        return send("DELETE", path, null, token);
    }

    Response send(String method, String path, Object body, String token, String... headers) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(path.startsWith("http") ? path : baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            for (int i = 0; i + 1 < headers.length; i += 2) {
                request.header(headers[i], headers[i + 1]);
            }
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json = response.body() == null || response.body().isBlank() ? null : tryParse(response.body());
            return new Response(response.statusCode(), json, response);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    HttpResponse<byte[]> putBytes(String url, byte[] body, String contentType) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    HttpResponse<byte[]> getBytes(String path, String token, String range) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Authorization", "Bearer " + token);
        if (range != null) {
            request.header("Range", range);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static JsonNode tryParse(String body) {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            return JSON.getNodeFactory().textNode(body);
        }
    }
}
