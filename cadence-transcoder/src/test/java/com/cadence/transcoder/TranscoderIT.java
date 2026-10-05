package com.cadence.transcoder;

import com.cadence.events.EventEnvelope;
import com.cadence.events.EventTypes;
import com.cadence.events.HlsLayout;
import com.cadence.events.ItemTypes;
import com.cadence.events.Topics;
import com.cadence.events.TrackUploadedPayload;
import com.cadence.events.UuidV7;
import com.cadence.transcoder.ffmpeg.Ffmpeg;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Real FFmpeg on a generated sine tone, real Kafka, real MinIO (spec 3.3 steps 4-5). Requires ffmpeg on PATH. */
@SpringBootTest(properties = "cadence.transcoder.timeout=2m")
@Import(TranscoderIT.Containers.class)
class TranscoderIT {

    static final String RAW = "cadence-raw";
    static final Path WORK_DIR = createDir("cadence-transcoder-it");

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        KafkaContainer kafka() {
            return new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));
        }

        @Bean
        MinIOContainer minio() {
            return new MinIOContainer(DockerImageName.parse("pgsty/minio:RELEASE.2026-08-04T00-00-00Z")
                    .asCompatibleSubstituteFor("minio/minio"));
        }

        @Bean
        DynamicPropertyRegistrar s3(MinIOContainer minio) {
            return r -> {
                r.add("cadence.s3.endpoint", minio::getS3URL);
                r.add("cadence.s3.access-key", minio::getUserName);
                r.add("cadence.s3.secret-key", minio::getPassword);
                r.add("cadence.s3.hls-bucket", () -> "cadence-hls");
                r.add("cadence.transcoder.work-dir", WORK_DIR::toString);
            };
        }
    }

    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    ConsumerFactory<String, String> consumerFactory;
    @Autowired
    S3Client s3;
    @Autowired
    ObjectMapper objectMapper;
    @MockitoSpyBean
    Ffmpeg ffmpeg;

    private Consumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

    @BeforeAll
    static void requireFfmpeg() throws Exception {
        assertThat(new ProcessBuilder("ffmpeg", "-version").start().waitFor())
                .as("ffmpeg must be installed for transcoder tests").isZero();
    }

    @BeforeEach
    void setUp() {
        for (String bucket : List.of(RAW, "cadence-hls")) {
            try {
                s3.headBucket(b -> b.bucket(bucket));
            } catch (NoSuchBucketException e) {
                s3.createBucket(b -> b.bucket(bucket));
            }
        }
        consumer = consumerFactory.createConsumer("transcoder-it-" + UUID.randomUUID(), null);
        consumer.subscribe(List.of(Topics.STREAMING_TRACK_TRANSCODED, Topics.STREAMING_TRACK_TRANSCODE_FAILED));
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    @Test
    void transcodesATenSecondToneIntoThreeHlsRenditions() throws Exception {
        UUID trackId = UuidV7.generate();
        EventEnvelope job = upload(trackId, sineMp3(10, 440), "mp3");

        EventEnvelope result = awaitResult(trackId);

        assertThat(result.eventType()).isEqualTo(EventTypes.TRACK_TRANSCODED);
        assertThat(result.payload().get("jobId").asText()).isEqualTo(job.eventId().toString());
        assertThat(result.payload().get("durationMs").asInt()).isBetween(9_900, 10_100);
        assertThat(result.payload().get("loudnessLufs").asDouble()).isBetween(-40.0, -5.0);
        assertThat(result.payload().get("bitratesKbps")).extracting(n -> n.asInt()).containsExactly(96, 160, 320);

        List<String> keys = keys(HlsLayout.prefix(trackId));
        assertThat(keys).contains(HlsLayout.masterKey(trackId), HlsLayout.fallbackKey(trackId), HlsLayout.resultKey(trackId));
        for (String variant : List.of("96k", "160k", "320k")) {
            assertThat(keys).contains(HlsLayout.variantKey(trackId, variant, "index.m3u8"),
                    HlsLayout.variantKey(trackId, variant, "segment_000.ts"));
            String playlist = text(HlsLayout.variantKey(trackId, variant, "index.m3u8"));
            assertThat(playlist).contains("#EXT-X-TARGETDURATION:10", "#EXT-X-PLAYLIST-TYPE:VOD", "#EXT-X-ENDLIST");
        }
        String master = text(HlsLayout.masterKey(trackId));
        assertThat(master).contains("96k/index.m3u8", "160k/index.m3u8", "320k/index.m3u8", "mp4a.40.2");
        assertThat(WORK_DIR).isEmptyDirectory();
    }

    @Test
    void duplicateDeliveryRepublishesWithoutTranscodingAgain() throws Exception {
        UUID trackId = UuidV7.generate();
        EventEnvelope job = upload(trackId, sineMp3(3, 880), "mp3");
        awaitResult(trackId);

        kafka.send(Topics.CATALOG_TRACK_UPLOADED, trackId.toString(), job.toJson(objectMapper)).get();

        await().atMost(Duration.ofSeconds(30)).until(() -> {
            poll();
            return received.stream().filter(r -> r.key().equals(trackId.toString())).count() == 2;
        });
        verify(ffmpeg, times(1)).transcode(any(), any(), any());
    }

    @Test
    void invalidAudioPublishesAFailureAndCleansUp() throws Exception {
        UUID trackId = UuidV7.generate();
        upload(trackId, "ID3 this is not really audio at all".getBytes(StandardCharsets.UTF_8), "mp3");

        EventEnvelope result = awaitResult(trackId);

        assertThat(result.eventType()).isEqualTo(EventTypes.TRACK_TRANSCODE_FAILED);
        assertThat(result.payload().get("reason").asText()).isNotBlank();
        assertThat(keys(HlsLayout.prefix(trackId))).isEmpty();
        assertThat(WORK_DIR).isEmptyDirectory();
    }

    @Test
    void missingSourcePublishesAFailure() {
        UUID trackId = UuidV7.generate();
        EventEnvelope job = EventEnvelope.create(EventTypes.TRACK_UPLOADED, Instant.now(), null, ItemTypes.SONG, trackId,
                new TrackUploadedPayload(RAW, "raw/" + trackId + "/source.mp3", "mp3", 10), objectMapper);
        kafka.send(Topics.CATALOG_TRACK_UPLOADED, trackId.toString(), job.toJson(objectMapper));

        EventEnvelope result = awaitResult(trackId);

        assertThat(result.eventType()).isEqualTo(EventTypes.TRACK_TRANSCODE_FAILED);
        assertThat(result.payload().get("reason").asText()).contains("does not exist");
    }

    private EventEnvelope upload(UUID trackId, byte[] audio, String ext) throws Exception {
        String key = "raw/" + trackId + "/source." + ext;
        s3.putObject(p -> p.bucket(RAW).key(key), RequestBody.fromBytes(audio));
        EventEnvelope job = EventEnvelope.create(EventTypes.TRACK_UPLOADED, Instant.now(), null, ItemTypes.SONG, trackId,
                new TrackUploadedPayload(RAW, key, ext, audio.length), objectMapper);
        kafka.send(Topics.CATALOG_TRACK_UPLOADED, trackId.toString(), job.toJson(objectMapper)).get();
        return job;
    }

    private EventEnvelope awaitResult(UUID trackId) {
        ConsumerRecord<String, String> record = await().atMost(Duration.ofSeconds(60)).until(() -> {
            poll();
            return received.stream().filter(r -> r.key().equals(trackId.toString())).findFirst().orElse(null);
        }, r -> r != null);
        return EventEnvelope.fromJson(record.value(), objectMapper);
    }

    private void poll() {
        consumer.poll(Duration.ofMillis(250)).forEach(received::add);
    }

    private List<String> keys(String prefix) {
        return s3.listObjectsV2Paginator(l -> l.bucket("cadence-hls").prefix(prefix)).contents().stream()
                .map(o -> o.key()).toList();
    }

    private String text(String key) {
        return s3.getObjectAsBytes(g -> g.bucket("cadence-hls").key(key)).asUtf8String();
    }

    static byte[] sineMp3(int seconds, int frequency) throws Exception {
        Path file = Files.createTempFile("tone", ".mp3");
        try {
            Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-nostdin", "-y", "-f", "lavfi",
                    "-i", "sine=frequency=" + frequency + ":duration=" + seconds, "-ac", "2", "-c:a", "libmp3lame",
                    "-b:a", "192k", file.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            assertThat(p.waitFor()).isZero();
            return Files.readAllBytes(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static Path createDir(String name) {
        try {
            return Files.createTempDirectory(name);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
