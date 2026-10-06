package com.cadence.common;

import com.cadence.IntegrationTest;
import com.cadence.support.ApiClient.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 7 observability (D98): Prometheus metrics and trace ids in every log line. Spring Boot tests switch metric
 * export off unless asked, hence its own context.
 */
@AutoConfigureObservability
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIT extends IntegrationTest {

    @Test
    void prometheusExposesLatencyHistogramsAndCadenceMetrics() {
        Session user = api.register();
        api.get("/api/v1/me/recommendations", user.accessToken());   // recommender disabled → fallback

        ResponseEntity<String> metrics = http.getForEntity("/actuator/prometheus", String.class);

        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metrics.getBody())
                .contains("http_server_requests_seconds_bucket{application=\"cadence-api\"")
                .containsPattern("http_server_requests_seconds_count\\{.*uri=\"/api/v1/me/recommendations\"")
                .contains("cadence_outbox_pending")
                .containsPattern("cadence_recommendations_served_total\\{.*source=\"fallback\"")
                .contains("resilience4j_circuitbreaker_state{application=\"cadence-api\",name=\"recommender\"")
                .contains("hikaricp_connections_active")
                .contains("jvm_memory_used_bytes");
    }

    @Test
    void logLinesCarryTheTraceId(CapturedOutput output) {
        Session user = api.register();
        api.refresh(user.refreshToken());
        api.refresh(user.refreshToken());   // reuse: AuthService logs a warning inside the request

        String line = output.getOut().lines().filter(l -> l.contains("Refresh token reuse detected")
                && l.contains(user.userId().toString())).findFirst().orElseThrow();
        assertThat(line).containsPattern(Pattern.compile("\\[cadence-api] \\[[^]]+] \\[[0-9a-f]{32}-[0-9a-f]{16}]"));
    }
}
