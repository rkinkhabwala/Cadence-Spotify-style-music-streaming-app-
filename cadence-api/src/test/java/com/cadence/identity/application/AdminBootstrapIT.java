package com.cadence.identity.application;

import com.cadence.IntegrationTest;
import com.cadence.identity.infrastructure.UserRepository;
import com.cadence.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class AdminBootstrapIT extends IntegrationTest {

    @Autowired
    AdminBootstrap bootstrap;
    @Autowired
    UserRepository users;

    @Test
    void adminFromEnvironmentCanLogInWithAdminRole() {
        String token = api.admin().accessToken();

        JsonNode me = api.get("/api/v1/me", token).getBody();

        assertThat(me.get("roles")).extracting(JsonNode::asText).containsExactlyInAnyOrder("ADMIN", "LISTENER");
    }

    @Test
    void bootstrapIsIdempotent() {
        boolean createdAgain = bootstrap.bootstrap();

        assertThat(createdAgain).isFalse();
        assertThat(users.findByEmail(ApiClient.ADMIN_EMAIL)).isPresent();
    }
}
