package com.cadence.support;

import com.cadence.common.storage.ObjectStorage;
import com.cadence.events.EventEnvelope;
import com.cadence.events.EventTypes;
import com.cadence.events.HlsLayout;
import com.cadence.events.ItemTypes;
import com.cadence.events.Topics;
import com.cadence.events.TrackTranscodedPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

/**
 * Makes READY tracks the way production does (upload → PROCESSING → {@code streaming.track-transcoded} event
 * consumed by the catalog), with synthetic HLS output written in the transcoder's exact layout and format.
 */
public class StreamingFixtures {

    public static final int DURATION_MS = 12_500;
    public static final int FALLBACK_SIZE = 5000;
    public static final String MASTER = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=104145,AVERAGE-BANDWIDTH=104654,CODECS="mp4a.40.2"
            96k/index.m3u8

            #EXT-X-STREAM-INF:BANDWIDTH=172975,AVERAGE-BANDWIDTH=173474,CODECS="mp4a.40.2"
            160k/index.m3u8

            #EXT-X-STREAM-INF:BANDWIDTH=227377,AVERAGE-BANDWIDTH=228050,CODECS="mp4a.40.2"
            320k/index.m3u8
            """;
    public static final String MEDIA = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:10.000000,
            segment_000.ts
            #EXTINF:2.500000,
            segment_001.ts
            #EXT-X-ENDLIST
            """;

    private final ApiClient api;
    private final String adminToken;
    private final ObjectStorage storage;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbc;

    public StreamingFixtures(ApiClient api, String adminToken, ObjectStorage storage, KafkaTemplate<String, String> kafka,
                             ObjectMapper objectMapper, JdbcClient jdbc) {
        this.api = api;
        this.adminToken = adminToken;
        this.storage = storage;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    /** A DRAFT track whose upload completed: PROCESSING with a transcode job id. */
    public UUID processingTrack(String title) {
        CatalogFixtures catalog = new CatalogFixtures(api, adminToken);
        UUID album = catalog.album(catalog.artist(title + " Artist"), title + " Album", java.time.LocalDate.of(2025, 1, 1));
        UUID track = catalog.track(album, title, 1);
        byte[] mp3 = ("ID3" + "x".repeat(64)).getBytes(StandardCharsets.US_ASCII);
        String key = api.post("/api/v1/admin/tracks/" + track + "/upload-url",
                Map.of("extension", "mp3", "sizeBytes", mp3.length), adminToken).getBody().get("objectKey").asText();
        storage.put(storage.rawBucket(), key, mp3, "audio/mpeg");
        api.post("/api/v1/admin/tracks/" + track + "/upload-complete", null, adminToken);
        return track;
    }

    public UUID jobIdOf(UUID trackId) {
        return jdbc.sql("SELECT transcode_job_id FROM tracks WHERE id = :id").param("id", trackId).query(UUID.class).single();
    }

    /** A READY track with HLS output and a fallback file in storage. */
    public UUID readyTrack(String title) {
        UUID track = processingTrack(title);
        writeHls(track);
        sendTranscoded(track, jobIdOf(track));
        awaitStatus(track, "READY");
        return track;
    }

    public void writeHls(UUID track) {
        storage.put(storage.hlsBucket(), HlsLayout.masterKey(track), MASTER.getBytes(StandardCharsets.UTF_8),
                "application/vnd.apple.mpegurl");
        for (int kbps : HlsLayout.BITRATES_KBPS) {
            String variant = HlsLayout.variantName(kbps);
            storage.put(storage.hlsBucket(), HlsLayout.variantKey(track, variant, HlsLayout.VARIANT_PLAYLIST),
                    MEDIA.getBytes(StandardCharsets.UTF_8), "application/vnd.apple.mpegurl");
            for (int segment = 0; segment < 2; segment++) {
                storage.put(storage.hlsBucket(), HlsLayout.variantKey(track, variant, "segment_00" + segment + ".ts"),
                        segmentBytes(variant, segment), "video/mp2t");
            }
        }
        storage.put(storage.hlsBucket(), HlsLayout.fallbackKey(track), fallbackBytes(track), "audio/mp4");
    }

    public EventEnvelope sendTranscoded(UUID track, UUID jobId) {
        EventEnvelope event = EventEnvelope.create(EventTypes.TRACK_TRANSCODED, Instant.now(), null, ItemTypes.SONG, track,
                new TrackTranscodedPayload(jobId, DURATION_MS, -14.5, HlsLayout.BITRATES_KBPS, storage.hlsBucket(),
                        HlsLayout.masterKey(track), HlsLayout.fallbackKey(track)), objectMapper);
        send(Topics.STREAMING_TRACK_TRANSCODED, event);
        return event;
    }

    public void send(String topic, EventEnvelope event) {
        try {
            kafka.send(topic, event.itemId().toString(), event.toJson(objectMapper)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void awaitStatus(UUID track, String status) {
        await().atMost(Duration.ofSeconds(20)).until(() ->
                status.equals(api.get("/api/v1/admin/tracks/" + track, adminToken).getBody().get("status").asText()));
    }

    public static byte[] segmentBytes(String variant, int segment) {
        return ("SEGMENT " + variant + " #" + segment).getBytes(StandardCharsets.US_ASCII);
    }

    /** Deterministic pseudo-random content per track, so Range responses can be compared byte by byte. */
    public static byte[] fallbackBytes(UUID track) {
        byte[] data = new byte[FALLBACK_SIZE];
        new java.util.Random(track.getLeastSignificantBits()).nextBytes(data);
        return data;
    }
}
