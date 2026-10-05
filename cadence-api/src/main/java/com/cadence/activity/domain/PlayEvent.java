package com.cadence.activity.domain;

import com.cadence.common.error.ConflictException;
import com.cadence.events.TrackPlayedPayload;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One playback of a track by a user (spec 4 PlayEvent). The client reports it as it progresses: once at 30 seconds
 * and again on completion or skip, always with the same id. Reports merge: {@code msPlayed} only grows and the
 * {@code completed}/{@code skipped} flags stick, so repeating a report changes nothing. The play counts as a stream
 * from the moment {@code msPlayed} first reaches 30 s ({@code countedAt}).
 */
@Entity
@Table(name = "play_events")
public class PlayEvent {

    @Id
    private UUID id;
    private UUID userId;
    private UUID trackId;
    private Instant startedAt;
    private int msPlayed;
    @Enumerated(EnumType.STRING)
    private PlaySource source;
    private UUID sourceId;
    private boolean completed;
    private boolean skipped;
    private Instant countedAt;
    private Instant updatedAt;

    protected PlayEvent() {
    }

    /** First report: the playback started {@code msPlayed} ago. */
    public PlayEvent(UUID id, UUID userId, UUID trackId, int msPlayed, PlaySource source, UUID sourceId,
                     boolean completed, boolean skipped, Instant now) {
        this.id = id;
        this.userId = userId;
        this.trackId = trackId;
        this.startedAt = now.minusMillis(msPlayed);
        this.msPlayed = msPlayed;
        this.source = source;
        this.sourceId = sourceId;
        this.completed = completed;
        this.skipped = skipped;
        this.countedAt = msPlayed >= TrackPlayedPayload.STREAM_THRESHOLD_MS ? now : null;
        this.updatedAt = now;
    }

    /**
     * Merges a later report of the same playback.
     *
     * @return whether anything changed (an identical or older report is a no-op)
     * @throws ConflictException ({@code play-mismatch}) if the id belongs to another user's or another track's playback
     */
    public boolean report(UUID reportingUser, UUID reportedTrack, int reportedMs, boolean reportedCompleted,
                          boolean reportedSkipped, Instant now) {
        if (!userId.equals(reportingUser) || !trackId.equals(reportedTrack)) {
            throw new ConflictException("play-mismatch", "playId " + id + " belongs to a different playback");
        }
        boolean changed = false;
        if (reportedMs > msPlayed) {
            msPlayed = reportedMs;
            changed = true;
        }
        if (reportedCompleted && !completed) {
            completed = true;
            changed = true;
        }
        if (reportedSkipped && !skipped) {
            skipped = true;
            changed = true;
        }
        if (countedAt == null && msPlayed >= TrackPlayedPayload.STREAM_THRESHOLD_MS) {
            countedAt = now;
        }
        if (changed) {
            updatedAt = now;
        }
        return changed;
    }

    public TrackPlayedPayload toPayload() {
        return new TrackPlayedPayload(id, msPlayed, completed, skipped, source.name(), sourceId);
    }

    public boolean isCounted() {
        return countedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTrackId() {
        return trackId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public int getMsPlayed() {
        return msPlayed;
    }

    public PlaySource getSource() {
        return source;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public boolean isCompleted() {
        return completed;
    }

    public boolean isSkipped() {
        return skipped;
    }

    public Instant getCountedAt() {
        return countedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
