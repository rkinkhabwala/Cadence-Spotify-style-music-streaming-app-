package com.cadence.catalog.application;

import com.cadence.catalog.TrackStatus;
import com.cadence.catalog.application.CatalogViews.AdminTrackView;
import com.cadence.catalog.application.CatalogViews.UploadUrl;
import com.cadence.catalog.domain.AudioFormat;
import com.cadence.catalog.domain.Track;
import com.cadence.catalog.infrastructure.TrackRepository;
import com.cadence.common.error.BadRequestException;
import com.cadence.common.error.ConflictException;
import com.cadence.common.error.NotFoundException;
import com.cadence.common.storage.ObjectStorage;
import com.cadence.common.storage.ObjectStorage.ObjectInfo;
import com.cadence.common.storage.ObjectStorage.PresignedRequest;
import com.cadence.events.EntityChangedPayload.Action;
import com.cadence.events.ItemTypes;
import com.cadence.events.TrackUploadedPayload;
import com.cadence.events.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.UUID;

/** Admin upload flow (spec 3.3): presigned PUT → upload-complete → transcoding via the outbox. */
@Service
public class TrackUploadService {

    private final TrackRepository tracks;
    private final ObjectStorage storage;
    private final CatalogEvents events;
    private final CatalogMapper mapper;
    private final TrackSummaries summaries;
    private final CatalogProperties properties;
    private final Clock clock;

    TrackUploadService(TrackRepository tracks, ObjectStorage storage, CatalogEvents events, CatalogMapper mapper,
                       TrackSummaries summaries, CatalogProperties properties, Clock clock) {
        this.tracks = tracks;
        this.storage = storage;
        this.events = events;
        this.mapper = mapper;
        this.summaries = summaries;
        this.properties = properties;
        this.clock = clock;
    }

    /** Presigned PUT to {@code raw/{trackId}/source.{ext}} in the raw bucket, signed for exactly {@code sizeBytes}. */
    @Transactional
    public UploadUrl uploadUrl(UUID trackId, String extension, long sizeBytes) {
        AudioFormat format = AudioFormat.fromExtension(extension).orElseThrow(() -> new BadRequestException(
                "unsupported-format", "Supported formats: mp3, flac, wav, m4a"));
        requireAllowedSize(sizeBytes);
        Track track = load(trackId);
        String key = sourceKey(trackId, format);
        track.prepareUpload(key, clock.instant());
        PresignedRequest put = storage.presignPut(storage.rawBucket(), key, format.mimeType(), sizeBytes,
                properties.uploadUrlTtl());
        return new UploadUrl(put.url(), put.method(), put.headers(), key, put.expiresAt());
    }

    /**
     * Verifies the uploaded object (exists, size, magic bytes) and starts transcoding. Idempotent: repeating it
     * while the track is PROCESSING returns the current state without starting another job.
     */
    @Transactional
    public AdminTrackView complete(UUID trackId) {
        Track track = load(trackId);
        if (track.getStatus() == TrackStatus.PROCESSING) {
            return mapper.toAdminView(track);
        }
        if (track.getSourceKey() == null) {
            throw sourceMissing();
        }
        ObjectInfo source = verifySource(track.getSourceKey());
        start(track, source, false);
        return mapper.toAdminView(track);
    }

    /** Re-runs transcoding of a FAILED track from its stored source. */
    @Transactional
    public AdminTrackView retranscode(UUID trackId) {
        Track track = load(trackId);
        if (track.getStatus() != TrackStatus.FAILED) {
            throw new ConflictException("track-not-failed",
                    "Only FAILED tracks can be retranscoded (status is " + track.getStatus() + ")");
        }
        if (track.getSourceKey() == null) {
            throw sourceMissing();
        }
        ObjectInfo source = storage.head(storage.rawBucket(), track.getSourceKey()).orElseThrow(this::sourceMissing);
        start(track, source, true);
        return mapper.toAdminView(track);
    }

    private void start(Track track, ObjectInfo source, boolean retry) {
        UUID jobId = UuidV7.generate();
        if (retry) {
            track.retranscode(jobId, clock.instant());
        } else {
            track.startProcessing(jobId, clock.instant());
        }
        tracks.flush();
        String extension = track.getSourceKey().substring(track.getSourceKey().lastIndexOf('.') + 1);
        events.trackUploaded(track.getId(), jobId,
                new TrackUploadedPayload(storage.rawBucket(), track.getSourceKey(), extension, source.sizeBytes()));
        events.entityChanged(ItemTypes.SONG, track.getId(), Action.UPDATED, summaries.snapshot(track));
    }

    private ObjectInfo verifySource(String key) {
        ObjectInfo info = storage.head(storage.rawBucket(), key).orElseThrow(this::sourceMissing);
        if (info.sizeBytes() > properties.maxUploadBytes()) {
            storage.delete(storage.rawBucket(), key);
            throw new BadRequestException("upload-too-large", "Uploads are limited to " + properties.maxUploadBytes() + " bytes");
        }
        AudioFormat format = AudioFormat.fromExtension(key.substring(key.lastIndexOf('.') + 1)).orElseThrow();
        byte[] header;
        try (InputStream in = storage.readRange(storage.rawBucket(), key, 0, AudioFormat.HEADER_BYTES - 1)) {
            header = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!format.matches(header)) {
            storage.delete(storage.rawBucket(), key);
            throw new BadRequestException("invalid-audio",
                    "The uploaded file is not a valid " + format.extension() + " file; upload it again");
        }
        return info;
    }

    private void requireAllowedSize(long sizeBytes) {
        if (sizeBytes <= 0 || sizeBytes > properties.maxUploadBytes()) {
            throw new BadRequestException("upload-too-large",
                    "sizeBytes must be between 1 and " + properties.maxUploadBytes());
        }
    }

    static String sourceKey(UUID trackId, AudioFormat format) {
        return "raw/" + trackId + "/source." + format.extension();
    }

    private ConflictException sourceMissing() {
        return new ConflictException("source-not-uploaded", "No uploaded audio found; request an upload URL and upload first");
    }

    private Track load(UUID id) {
        return tracks.findById(id).orElseThrow(() -> new NotFoundException("Track", id));
    }
}
