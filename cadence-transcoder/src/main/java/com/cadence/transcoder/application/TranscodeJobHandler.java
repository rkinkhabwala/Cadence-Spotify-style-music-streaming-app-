package com.cadence.transcoder.application;

import com.cadence.events.EventEnvelope;
import com.cadence.events.HlsLayout;
import com.cadence.events.TrackTranscodedPayload;
import com.cadence.events.TrackUploadedPayload;
import com.cadence.transcoder.config.TranscoderProperties;
import com.cadence.transcoder.ffmpeg.Ffmpeg;
import com.cadence.transcoder.messaging.ResultPublisher;
import com.cadence.transcoder.storage.HlsStorage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Transcodes one uploaded track (spec 3.3 steps 4-5). Idempotent: if {@code result.json} for the same job already
 * exists the stored result is republished without re-running FFmpeg. The per-job temp directory is always removed.
 */
@Component
public class TranscodeJobHandler {

    private static final Logger log = LoggerFactory.getLogger(TranscodeJobHandler.class);

    private final Ffmpeg ffmpeg;
    private final HlsStorage storage;
    private final ResultPublisher publisher;
    private final TranscoderProperties properties;
    private final ObjectMapper objectMapper;

    public TranscodeJobHandler(Ffmpeg ffmpeg, HlsStorage storage, ResultPublisher publisher,
                               TranscoderProperties properties, ObjectMapper objectMapper) {
        this.ffmpeg = ffmpeg;
        this.storage = storage;
        this.publisher = publisher;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void handle(EventEnvelope event) {
        UUID trackId = event.itemId();
        UUID jobId = event.eventId();
        TrackUploadedPayload job = event.payloadAs(TrackUploadedPayload.class, objectMapper);

        Optional<TrackTranscodedPayload> done = previousResult(trackId, jobId);
        if (done.isPresent()) {
            log.info("Job {} for track {} already transcoded; republishing result", jobId, trackId);
            publisher.transcoded(trackId, done.get());
            return;
        }

        Path work = createWorkDir();
        long started = System.nanoTime();
        try {
            TrackTranscodedPayload result = transcode(trackId, jobId, job, work);
            publisher.transcoded(trackId, result);
            log.info("Transcoded track {} (job {}) in {} ms: {} ms audio, {} LUFS", trackId, jobId,
                    (System.nanoTime() - started) / 1_000_000, result.durationMs(), result.loudnessLufs());
        } catch (TranscodeException e) {
            log.warn("Transcoding track {} (job {}) failed: {}", trackId, jobId, e.getMessage());
            publisher.failed(trackId, jobId, e.getMessage());
        } finally {
            FileSystemUtils.deleteRecursively(work.toFile());
        }
    }

    private TrackTranscodedPayload transcode(UUID trackId, UUID jobId, TrackUploadedPayload job, Path work) {
        Path source = work.resolve("source." + job.extension());
        if (!storage.download(job.bucket(), job.sourceKey(), source)) {
            throw new TranscodeException("Source object " + job.sourceKey() + " does not exist");
        }
        int durationMs = ffmpeg.probeDurationMs(source, work);
        Path out = work.resolve("out");
        ffmpeg.transcode(source, out, work);
        OptionalDouble loudness = ffmpeg.loudnessLufs(source, work);

        String prefix = HlsLayout.prefix(trackId);
        storage.deletePrefix(prefix); // no stale segments from an earlier, longer upload
        storage.uploadDirectory(out, prefix);
        TrackTranscodedPayload result = new TrackTranscodedPayload(jobId, durationMs,
                loudness.isPresent() ? loudness.getAsDouble() : null, HlsLayout.BITRATES_KBPS, storage.hlsBucket(),
                HlsLayout.masterKey(trackId), HlsLayout.fallbackKey(trackId));
        storage.putText(HlsLayout.resultKey(trackId), toJson(result), "application/json"); // written last
        return result;
    }

    private Optional<TrackTranscodedPayload> previousResult(UUID trackId, UUID jobId) {
        return storage.readText(HlsLayout.resultKey(trackId)).map(json -> {
            try {
                return objectMapper.readValue(json, TrackTranscodedPayload.class);
            } catch (JsonProcessingException e) {
                return null;
            }
        }).filter(result -> jobId.equals(result.jobId()));
    }

    private Path createWorkDir() {
        try {
            Path parent = properties.effectiveWorkDir();
            Files.createDirectories(parent);
            return Files.createTempDirectory(parent, "cadence-transcode-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
