package com.cadence;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** Real infrastructure for integration tests; images match docker-compose.yml. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));
    }

    /** Same MinIO build as docker-compose.yml (DECISIONS.md D3); only the plain S3 API is used. */
    @Bean
    MinIOContainer minio() {
        return new MinIOContainer(DockerImageName.parse("pgsty/minio:RELEASE.2026-08-04T00-00-00Z")
                .asCompatibleSubstituteFor("minio/minio"))
                .withUserName("cadence-test")
                .withPassword("cadence-test-secret");
    }

    @Bean
    DynamicPropertyRegistrar s3Properties(MinIOContainer minio) {
        return registry -> {
            registry.add("cadence.s3.endpoint", minio::getS3URL);
            registry.add("cadence.s3.public-endpoint", minio::getS3URL);
            registry.add("cadence.s3.access-key", minio::getUserName);
            registry.add("cadence.s3.secret-key", minio::getPassword);
            registry.add("cadence.s3.auto-create-buckets", () -> "true");
        };
    }

    /** Same version as docker-compose.yml; security off in tests (Compose uses basic auth). */
    @Bean
    @ServiceConnection
    ElasticsearchContainer elasticsearch() {
        return new ElasticsearchContainer(DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.19.22"))
                .withEnv("xpack.security.enabled", "false")
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    }
}
