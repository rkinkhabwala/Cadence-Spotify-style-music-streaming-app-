package com.cadence.events;

/**
 * Payload of {@code catalog.track-uploaded}. The envelope's {@code eventId} is the transcode job id and
 * {@code itemId} the track id.
 *
 * @param bucket     bucket holding the raw upload
 * @param sourceKey  object key, e.g. {@code raw/{trackId}/source.mp3}
 * @param extension  mp3 | flac | wav | m4a
 * @param sizeBytes  size of the source object
 */
public record TrackUploadedPayload(String bucket, String sourceKey, String extension, long sizeBytes) {
}
