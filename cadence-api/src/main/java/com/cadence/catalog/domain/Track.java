package com.cadence.catalog.domain;

import com.cadence.catalog.TrackStatus;
import com.cadence.common.error.BadRequestException;
import com.cadence.common.error.ConflictException;
import com.cadence.events.UuidV7;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A recording. Lifecycle: DRAFT → (upload-complete) PROCESSING → READY | FAILED; a FAILED track can be
 * retranscoded and any non-PROCESSING track can get a new upload. Every transcode run has a job id so a
 * stale result from an earlier run is ignored.
 */
@Entity
@Table(name = "tracks")
public class Track {

    @Id
    private UUID id;
    private String title;
    private UUID albumId;
    private int trackNumber;
    private int discNumber;
    private Integer durationMs;
    private boolean explicit;
    @Enumerated(EnumType.STRING)
    private TrackStatus status;
    private long playCount;
    private String isrc;
    private String sourceKey;
    private UUID transcodeJobId;
    private Double loudnessLufs;
    private String failureReason;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "track_artists", joinColumns = @JoinColumn(name = "track_id"))
    private List<TrackArtist> artists = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private long version;

    protected Track() {
    }

    public Track(String title, UUID albumId, int trackNumber, int discNumber, boolean explicit, String isrc,
                 List<TrackArtist> artists, Instant now) {
        this.id = UuidV7.generate();
        this.title = title.strip();
        this.albumId = albumId;
        this.trackNumber = trackNumber;
        this.discNumber = discNumber;
        this.explicit = explicit;
        this.isrc = Texts.blankToNull(isrc);
        this.status = TrackStatus.DRAFT;
        setArtists(artists);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String title, Integer trackNumber, Integer discNumber, Boolean explicit, String isrc,
                       List<TrackArtist> artists, Instant now) {
        if (title != null) {
            this.title = title.strip();
        }
        if (trackNumber != null) {
            this.trackNumber = trackNumber;
        }
        if (discNumber != null) {
            this.discNumber = discNumber;
        }
        if (explicit != null) {
            this.explicit = explicit;
        }
        if (isrc != null) {
            this.isrc = Texts.blankToNull(isrc);
        }
        if (artists != null) {
            setArtists(artists);
        }
        this.updatedAt = now;
    }

    private void setArtists(List<TrackArtist> credits) {
        if (credits.stream().noneMatch(c -> c.role() == ArtistRole.PRIMARY)) {
            throw new BadRequestException("primary-artist-required", "A track needs at least one PRIMARY artist");
        }
        if (credits.stream().map(TrackArtist::artistId).distinct().count() != credits.size()) {
            throw new BadRequestException("duplicate-artist", "An artist can be credited only once per track");
        }
        this.artists = new ArrayList<>(credits);
    }

    /** Records where the next raw upload goes. Not allowed while a transcode is running. */
    public void prepareUpload(String sourceKey, Instant now) {
        requireNotProcessing();
        this.sourceKey = sourceKey;
        this.updatedAt = now;
    }

    /**
     * Upload finished: start a transcode job.
     *
     * @return {@code false} if this source is already being transcoded (idempotent repeat)
     */
    public boolean startProcessing(UUID jobId, Instant now) {
        if (sourceKey == null) {
            throw new ConflictException("source-not-uploaded", "Request an upload URL and upload the audio first");
        }
        if (status == TrackStatus.PROCESSING) {
            return false;
        }
        begin(jobId, now);
        return true;
    }

    /** Retry a FAILED transcode (spec 5). */
    public void retranscode(UUID jobId, Instant now) {
        if (status != TrackStatus.FAILED) {
            throw new ConflictException("track-not-failed", "Only FAILED tracks can be retranscoded (status is " + status + ")");
        }
        if (sourceKey == null) {
            throw new ConflictException("source-not-uploaded", "The track has no uploaded source");
        }
        begin(jobId, now);
    }

    private void begin(UUID jobId, Instant now) {
        this.status = TrackStatus.PROCESSING;
        this.transcodeJobId = jobId;
        this.failureReason = null;
        this.updatedAt = now;
    }

    /** @return {@code false} if the result belongs to another (older) job or the track is not processing */
    public boolean markReady(UUID jobId, int durationMs, Double loudnessLufs, Instant now) {
        if (!isCurrentJob(jobId)) {
            return false;
        }
        this.status = TrackStatus.READY;
        this.durationMs = durationMs;
        this.loudnessLufs = loudnessLufs;
        this.updatedAt = now;
        return true;
    }

    public boolean markFailed(UUID jobId, String reason, Instant now) {
        if (!isCurrentJob(jobId)) {
            return false;
        }
        this.status = TrackStatus.FAILED;
        this.failureReason = reason == null ? "unknown error" : reason.substring(0, Math.min(reason.length(), 2000));
        this.updatedAt = now;
        return true;
    }

    private boolean isCurrentJob(UUID jobId) {
        return status == TrackStatus.PROCESSING && jobId.equals(transcodeJobId);
    }

    private void requireNotProcessing() {
        if (status == TrackStatus.PROCESSING) {
            throw new ConflictException("track-processing", "The track is being transcoded; wait for it to finish");
        }
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public UUID getAlbumId() {
        return albumId;
    }

    public int getTrackNumber() {
        return trackNumber;
    }

    public int getDiscNumber() {
        return discNumber;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public boolean isExplicit() {
        return explicit;
    }

    public TrackStatus getStatus() {
        return status;
    }

    public long getPlayCount() {
        return playCount;
    }

    public String getIsrc() {
        return isrc;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public UUID getTranscodeJobId() {
        return transcodeJobId;
    }

    public Double getLoudnessLufs() {
        return loudnessLufs;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public List<TrackArtist> getArtists() {
        return List.copyOf(artists);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
