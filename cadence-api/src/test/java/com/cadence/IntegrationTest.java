package com.cadence;

import com.cadence.support.ApiClient;
import com.cadence.support.TestKeys;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for full-context integration tests. Every IT shares this exact configuration so Spring
 * caches one context (and one set of containers) for the whole run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "cadence.admin.email=" + ApiClient.ADMIN_EMAIL,
        "cadence.admin.password=" + ApiClient.ADMIN_PASSWORD,
        "cadence.security.jwt.issuer=cadence"
})
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    @Autowired
    protected TestRestTemplate http;

    protected ApiClient api;

    @DynamicPropertySource
    static void jwtKeys(DynamicPropertyRegistry registry) {
        registry.add("cadence.security.jwt.private-key-location", () -> "file:" + TestKeys.privateKeyFile());
        registry.add("cadence.security.jwt.public-key-location", () -> "file:" + TestKeys.publicKeyFile());
    }

    @BeforeEach
    void apiClient() {
        api = new ApiClient(http);
    }
}
