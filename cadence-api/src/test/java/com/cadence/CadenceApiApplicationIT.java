package com.cadence;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

class CadenceApiApplicationIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void healthIsUp() {
        ResponseEntity<JsonNode> response = http.getForEntity("/actuator/health", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status").asText()).isEqualTo("UP");
    }

    @Test
    void flywayAppliedTheBaseline() {
        Integer applied = jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success AND version = '1'")
                .query(Integer.class).single();
        Long outboxTables = jdbc.sql("""
                        SELECT count(*) FROM information_schema.tables
                        WHERE table_name IN ('outbox_event', 'processed_event')
                        """)
                .query(Long.class).single();

        assertThat(applied).isEqualTo(1);
        assertThat(outboxTables).isEqualTo(2);
    }

    @Test
    void openApiDocsAndSwaggerUiAreServed() {
        ResponseEntity<JsonNode> docs = http.getForEntity("/v3/api-docs", JsonNode.class);
        ResponseEntity<String> ui = http.getForEntity("/swagger-ui/index.html", String.class);

        assertThat(docs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(docs.getBody().at("/info/title").asText()).isEqualTo("Cadence API");
        assertThat(ui.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ui.getBody()).contains("swagger-ui");
    }

    @Test
    void devPlayerIsNotServedWithoutTheDevProfile() {
        assertThat(http.getForEntity("/dev/player.html", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unauthenticatedRequestReturnsProblemDetail() {
        ResponseEntity<JsonNode> response = http.getForEntity("/api/v1/does-not-exist", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().get("code").asText()).isEqualTo("unauthorized");
    }

    @Test
    void unknownRouteReturnsProblemDetail() {
        ResponseEntity<JsonNode> response = api.get("/api/v1/does-not-exist", api.register().accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().get("code").asText()).isEqualTo("not-found");
        assertThat(response.getBody().get("type").asText()).isEqualTo("https://cadence.dev/problems/not-found");
        assertThat(response.getBody().get("status").asInt()).isEqualTo(404);
    }
}
