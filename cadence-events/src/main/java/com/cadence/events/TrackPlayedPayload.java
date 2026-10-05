package com.cadence.events;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.UUID;

/**
 * Payload of {@code activity.track-played}. One playback ({@code playId}) can be reported several times as it
 * progresses (at 30 s, then on completion or skip); every report carries the playback's state so far.
 *
 * @param playId           the playback this report belongs to; consumers deduplicate per playback with it
 * @param msPlayed         milliseconds actually listened so far
 * @param source           where the play started: PLAYLIST, ALBUM, SEARCH, RADIO, ARTIST, LIBRARY or OTHER
 * @param sourceId         id of the playlist/album/artist the play started from, if any
 * @param durationMs       the track's duration, so consumers can compute completion % and skip position (D88)
 * @param sessionId        the client's listening session (one per app tab), if reported
 * @param recommendationId the recommender's id of the list the track was played from, for attribution
 * @param position         0-based slot of the track in that recommended list
 */
public record TrackPlayedPayload(UUID playId, long msPlayed, boolean completed, boolean skipped, String source,
                                 UUID sourceId, Integer durationMs, String sessionId, String recommendationId,
                                 Integer position) {

    /** Spec 4: a play counts as a stream (play count, top tracks) once 30 seconds were played. */
    public static final long STREAM_THRESHOLD_MS = 30_000;

    @JsonIgnore
    public boolean isStream() {
        return msPlayed >= STREAM_THRESHOLD_MS;
    }
}
