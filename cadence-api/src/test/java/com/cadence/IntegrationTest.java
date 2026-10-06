package com.cadence;

import com.cadence.support.ApiClient;
import com.cadence.support.TestKeys;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for full-context integration tests. Every IT shares this exact configuration so Spring
 * caches one context (and one set of containers) for the whole run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "cadence.admin.email=" + ApiClient.ADMIN_EMAIL,
        "cadence.admin.password=" + ApiClient.ADMIN_PASSWORD,
        "cadence.security.jwt.issuer=cadence",
        // fixtures create entities in tight loops; the global API limit itself is tested by RateLimitIT
        "cadence.rate-limits.api.capacity=1000000"
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

    /**
     * Pins the JDK HttpClient: Apache HttpClient 5 (on the classpath via the AWS SDK) would otherwise be picked and
     * its default retry strategy silently waits out {@code Retry-After} and retries 429/503, hiding them from tests.
     */
    @BeforeEach
    void apiClient() {
        http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        api = new ApiClient(http);
    }
}
