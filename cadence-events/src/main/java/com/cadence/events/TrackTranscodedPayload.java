package com.cadence.events;

import java.util.List;
import java.util.UUID;

/**
 * Payload of {@code streaming.track-transcoded}.
 *
 * @param jobId         event id of the {@code catalog.track-uploaded} event that started the job
 * @param durationMs    duration of the source audio
 * @param loudnessLufs  EBU R128 integrated loudness (LUFS), {@code null} if it could not be measured
 * @param bitratesKbps  AAC renditions produced, e.g. [96, 160, 320]
 * @param bucket        bucket holding the HLS output
 * @param masterKey     key of the master playlist, e.g. {@code hls/{trackId}/master.m3u8}
 * @param fallbackKey   key of the single-file 160 kbps rendition used by the Range endpoint
 */
public record TrackTranscodedPayload(
        UUID jobId,
        int durationMs,
        Double loudnessLufs,
        List<Integer> bitratesKbps,
        String bucket,
        String masterKey,
        String fallbackKey) {
}
