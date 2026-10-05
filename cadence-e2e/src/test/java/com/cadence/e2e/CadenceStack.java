package com.cadence.e2e;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The whole Cadence system for acceptance tests: real infrastructure in containers plus the packaged API and
 * transcoder jars running as separate JVM processes, configured exactly like a deployment (command-line properties,
 * no .env, dev profile so the hls.js page is served).
 */
final class CadenceStack implements AutoCloseable {

    static final String ADMIN_EMAIL = "admin@e2e.test";
    static final String ADMIN_PASSWORD = "e2e-admin-password";

    final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));
    final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    final MinIOContainer minio = new MinIOContainer(DockerImageName.parse("pgsty/minio:RELEASE.2026-08-04T00-00-00Z")
            .asCompatibleSubstituteFor("minio/minio"));
    final ElasticsearchContainer elasticsearch = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.19.22"))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    final KeyPair signingKeys;
    final int apiPort;
    private final List<Process> processes = new ArrayList<>();
    private final Path logs;

    private CadenceStack() throws Exception {
        signingKeys = generateKeys();
        apiPort = freePort();
        logs = Path.of(System.getProperty("cadence.e2e.logs", "target/e2e-logs"));
        Files.createDirectories(logs);
    }

    static CadenceStack start() throws Exception {
        CadenceStack stack = new CadenceStack();
        try {
            Startables.deepStart(stack.postgres, stack.kafka, stack.redis, stack.minio, stack.elasticsearch).join();
            stack.startApps();
            return stack;
        } catch (Exception | Error e) {
            stack.close();
            throw e;
        }
    }

    String apiUrl() {
        return "http://localhost:" + apiPort;
    }

    S3Client s3() {
        return S3Client.builder().endpointOverride(URI.create(minio.getS3URL())).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(minio.getUserName(), minio.getPassword())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
    }

    private void startApps() throws Exception {
        Path work = Files.createTempDirectory("cadence-e2e");
        Path privateKey = Files.writeString(work.resolve("jwt-private.pem"), pem("PRIVATE KEY", signingKeys.getPrivate().getEncoded()));
        Path publicKey = Files.writeString(work.resolve("jwt-public.pem"), pem("PUBLIC KEY", signingKeys.getPublic().getEncoded()));
        String s3 = minio.getS3URL();
        int transcoderPort = freePort();

        launch("api", System.getProperty("cadence.api.jar"), work, List.of(
                "--server.port=" + apiPort,
                "--spring.profiles.active=dev",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                "--spring.data.redis.host=" + redis.getHost(),
                "--spring.data.redis.port=" + redis.getMappedPort(6379),
                "--spring.data.redis.password=",
                "--spring.elasticsearch.uris=http://" + elasticsearch.getHttpHostAddress(),
                "--spring.elasticsearch.password=",
                "--cadence.s3.endpoint=" + s3,
                "--cadence.s3.public-endpoint=" + s3,
                "--cadence.s3.access-key=" + minio.getUserName(),
                "--cadence.s3.secret-key=" + minio.getPassword(),
                "--cadence.s3.auto-create-buckets=true",
                "--cadence.security.jwt.private-key-location=file:" + privateKey,
                "--cadence.security.jwt.public-key-location=file:" + publicKey,
                "--cadence.admin.email=" + ADMIN_EMAIL,
                "--cadence.admin.password=" + ADMIN_PASSWORD,
                "--cadence.outbox.poll-interval=200ms"));
        awaitHealthy("api", apiPort);

        launch("transcoder", System.getProperty("cadence.transcoder.jar"), work, List.of(
                "--server.port=" + transcoderPort,
                "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                "--cadence.s3.endpoint=" + s3,
                "--cadence.s3.access-key=" + minio.getUserName(),
                "--cadence.s3.secret-key=" + minio.getPassword(),
                "--cadence.transcoder.work-dir=" + work.resolve("transcoder")));
        awaitHealthy("transcoder", transcoderPort);
    }

    private void launch(String name, String jar, Path workDir, List<String> args) throws IOException {
        if (jar == null || !Files.exists(Path.of(jar))) {
            throw new IllegalStateException("Missing " + jar + ": build the whole reactor (./mvnw verify)");
        }
        String java = ProcessHandle.current().info().command().orElse("java"); // the toolchain JDK 21
        List<String> command = new ArrayList<>(List.of(java, "-Xmx512m", "-jar", jar));
        command.addAll(args);
        Process process = new ProcessBuilder(command).directory(workDir.toFile())
                .redirectErrorStream(true).redirectOutput(logs.resolve(name + ".log").toFile()).start();
        processes.add(process);
    }

    private void awaitHealthy(String name, int port) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(120));
        while (Instant.now().isBefore(deadline)) {
            if (processes.getLast().isAlive()) {
                try {
                    HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/actuator/health")).build(), HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        return;
                    }
                } catch (IOException notYet) {
                    // still starting
                }
            } else {
                break;
            }
            Thread.sleep(500);
        }
        String log = Files.readString(logs.resolve(name + ".log"));
        throw new IllegalStateException(name + " did not become healthy:\n" + log.substring(Math.max(0, log.length() - 4000)));
    }

    @Override
    public void close() {
        processes.forEach(p -> {
            p.destroy();
            try {
                if (!p.waitFor(java.util.concurrent.TimeUnit.SECONDS.toMillis(15), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        List.of(elasticsearch, minio, redis, kafka, postgres).forEach(GenericContainer::stop);
    }

    private static KeyPair generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
