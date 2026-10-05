package com.cadence.events;

import java.util.UUID;

/** Payload of {@code streaming.track-transcode-failed}. */
public record TrackTranscodeFailedPayload(UUID jobId, String reason) {
}
