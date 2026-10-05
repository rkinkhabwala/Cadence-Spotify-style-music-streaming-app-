package com.cadence.activity.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * HTTP client of the recommender's {@code GET /v1/recommendations} (spec 3.5, D88, D89).
 *
 * <p>Built on the JDK HttpClient with explicit timeouts, never on Spring's auto-detected request factory: with the AWS
 * SDK on the classpath that would be Apache HttpClient 5, whose default retry strategy silently waits out
 * {@code Retry-After} and retries 429/503 (D37). Attempts are explicit ({@code max-attempts}, default 1 = no retry, and
 * only connection failures are ever retried). A Resilience4j circuit breaker stops calling a failing recommender.
 * Every failure is reported as {@link Optional#empty()}, so the caller falls back; nothing here throws.
 */
@Component
public class RecommenderClient {

    /** {@code context}: the recommender's surface ({@code home}, {@code radio}, ...); {@code seedTrackId} optional. */
    public record Request(UUID userId, String context, int limit, UUID seedTrackId) {
    }

    /** {@code position}: the item's slot in the recommender's list, echoed back in play reports for attribution. */
    public record Item(UUID trackId, int position, String reasonCode) {
    }

    public record Result(String recommendationId, String variantId, String fallbackLevel, List<Item> items) {
    }

    private static final Logger log = LoggerFactory.getLogger(RecommenderClient.class);
    static final String API_KEY_HEADER = "X-Api-Key";

    private final RecommenderProperties props;
    private final ObjectMapper objectMapper;
    private final RestClient http;
    private final CircuitBreaker breaker;
    private final MeterRegistry meters;

    RecommenderClient(RecommenderProperties props, ObjectMapper objectMapper, MeterRegistry meters) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.meters = meters;
        HttpClient jdk = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(props.readTimeout());
        RestClient.Builder builder = RestClient.builder().requestFactory(factory).baseUrl(props.baseUrl());
        if (props.apiKey() != null && !props.apiKey().isBlank()) {
            builder.defaultHeader(API_KEY_HEADER, props.apiKey());
        }
        this.http = builder.build();
        RecommenderProperties.CircuitBreaker cb = props.circuitBreaker();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cb.slidingWindowSize())
                .minimumNumberOfCalls(cb.minimumNumberOfCalls())
                .failureRateThreshold(cb.failureRateThreshold())
                .slowCallRateThreshold(cb.failureRateThreshold())
                .slowCallDurationThreshold(cb.slowCallDurationThreshold())
                .waitDurationInOpenState(cb.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(cb.permittedCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .build());
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        this.breaker = registry.circuitBreaker("recommender");
    }

    public boolean enabled() {
        return props.enabled();
    }

    /** @return the recommender's list, or empty when it is disabled, unavailable, too slow or answered an error */
    public Optional<Result> recommend(Request request) {
        if (!props.enabled()) {
            return record("disabled", Timer.start(meters), Optional.empty());
        }
        Timer.Sample sample = Timer.start(meters);
        try {
            Result result = breaker.executeSupplier(() -> call(request));
            return record(result.items().isEmpty() ? "empty" : "success", sample, Optional.of(result));
        } catch (CallNotPermittedException e) {
            return record("circuit-open", sample, Optional.empty());
        } catch (RestClientResponseException e) {
            log.warn("Recommender answered {} for user {}; using the fallback", e.getStatusCode(), request.userId());
            return record("http-" + e.getStatusCode().value(), sample, Optional.empty());
        } catch (ResourceAccessException e) {
            boolean timeout = e.getCause() instanceof HttpTimeoutException;
            log.warn("Recommender {} for user {}; using the fallback", timeout ? "timed out" : "unreachable",
                    request.userId());
            return record(timeout ? "timeout" : "unreachable", sample, Optional.empty());
        } catch (RuntimeException e) {
            log.warn("Recommender call failed for user {}: {}; using the fallback", request.userId(), e.toString());
            return record("error", sample, Optional.empty());
        }
    }

    public CircuitBreaker.State circuitState() {
        return breaker.getState();
    }

    /** Closes the circuit again (tests, operations). */
    public void resetCircuit() {
        breaker.reset();
    }

    private Result call(Request request) {
        for (int attempt = 1; ; attempt++) {
            try {
                String body = http.get()
                        .uri(uri -> {
                            uri.path("/v1/recommendations")
                                    .queryParam("userId", request.userId())
                                    .queryParam("domain", props.domain())
                                    .queryParam("context", request.context())
                                    .queryParam("limit", request.limit());
                            if (request.seedTrackId() != null) {
                                uri.queryParam("seedItemId", request.seedTrackId());
                            }
                            return uri.build();
                        })
                        .retrieve()
                        .body(String.class);
                return parse(body);
            } catch (ResourceAccessException e) {
                if (attempt >= props.maxAttempts() || !(e.getCause() instanceof ConnectException)) {
                    throw e;
                }
            }
        }
    }

    /** Items whose id isn't a Cadence track id (UUID) are ignored. */
    private Result parse(String body) {
        try {
            JsonNode json = objectMapper.readTree(body == null ? "{}" : body);
            List<Item> items = new ArrayList<>();
            int index = 0;
            for (JsonNode item : json.path("items")) {
                int position = item.path("position").isInt() ? item.get("position").asInt() : index;
                index++;
                try {
                    items.add(new Item(UUID.fromString(item.path("itemId").asText()), position,
                            item.path("reasonCode").asText(null)));
                } catch (IllegalArgumentException notATrackId) {
                    // not one of ours
                }
            }
            return new Result(json.path("recommendationId").asText(null), json.path("variantId").asText(null),
                    json.path("fallbackLevel").asText(null), List.copyOf(items));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Malformed recommender response", e);
        }
    }

    private <T> T record(String outcome, Timer.Sample sample, T value) {
        sample.stop(Timer.builder("cadence.recommender.requests")
                .description("Calls to the recommender's GET /v1/recommendations, by outcome")
                .tag("outcome", outcome)
                .register(meters));
        return value;
    }
}
