package com.cadence.catalog.domain;

import com.cadence.catalog.TrackStatus;
import com.cadence.common.error.BadRequestException;
import com.cadence.common.error.ConflictException;
import com.cadence.events.UuidV7;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrackTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final UUID ARTIST = UuidV7.generate();

    private Track draft() {
        return new Track("Song", UuidV7.generate(), 1, 1, false, null,
                List.of(new TrackArtist(ARTIST, ArtistRole.PRIMARY)), NOW);
    }

    @Test
    void newTracksAreDrafts() {
        assertThat(draft().getStatus()).isEqualTo(TrackStatus.DRAFT);
    }

    @Test
    void cannotStartProcessingWithoutAnUpload() {
        assertThatThrownBy(() -> draft().startProcessing(UuidV7.generate(), NOW))
                .isInstanceOf(ConflictException.class).hasMessageContaining("upload");
    }

    @Test
    void happyPathDraftProcessingReady() {
        Track track = draft();
        UUID job = UuidV7.generate();
        track.prepareUpload("raw/x/source.mp3", NOW);

        assertThat(track.startProcessing(job, NOW)).isTrue();
        assertThat(track.startProcessing(UuidV7.generate(), NOW)).as("idempotent while processing").isFalse();
        assertThat(track.getTranscodeJobId()).isEqualTo(job);
        assertThat(track.markReady(job, 183_000, -14.2, NOW)).isTrue();
        assertThat(track.getStatus()).isEqualTo(TrackStatus.READY);
        assertThat(track.getDurationMs()).isEqualTo(183_000);
    }

    @Test
    void resultsOfStaleJobsAreIgnored() {
        Track track = draft();
        track.prepareUpload("raw/x/source.mp3", NOW);
        track.startProcessing(UuidV7.generate(), NOW);

        assertThat(track.markReady(UuidV7.generate(), 1000, null, NOW)).isFalse();
        assertThat(track.markFailed(UuidV7.generate(), "boom", NOW)).isFalse();
        assertThat(track.getStatus()).isEqualTo(TrackStatus.PROCESSING);
    }

    @Test
    void failedTracksCanBeRetranscodedOthersCannot() {
        Track track = draft();
        UUID job = UuidV7.generate();
        track.prepareUpload("raw/x/source.mp3", NOW);
        track.startProcessing(job, NOW);
        track.markFailed(job, "ffmpeg exited with 1", NOW);
        UUID retry = UuidV7.generate();

        track.retranscode(retry, NOW);

        assertThat(track.getStatus()).isEqualTo(TrackStatus.PROCESSING);
        assertThat(track.getFailureReason()).isNull();
        assertThat(track.getTranscodeJobId()).isEqualTo(retry);
        assertThatThrownBy(() -> track.retranscode(UuidV7.generate(), NOW)).isInstanceOf(ConflictException.class);
    }

    @Test
    void noNewUploadWhileProcessing() {
        Track track = draft();
        track.prepareUpload("raw/x/source.mp3", NOW);
        track.startProcessing(UuidV7.generate(), NOW);

        assertThatThrownBy(() -> track.prepareUpload("raw/x/source.flac", NOW))
                .isInstanceOf(ConflictException.class).hasMessageContaining("transcoded");
    }

    @Test
    void creditsNeedAPrimaryArtistAndNoDuplicates() {
        UUID album = UuidV7.generate();
        assertThatThrownBy(() -> new Track("S", album, 1, 1, false, null,
                List.of(new TrackArtist(ARTIST, ArtistRole.FEATURED)), NOW))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> new Track("S", album, 1, 1, false, null,
                List.of(new TrackArtist(ARTIST, ArtistRole.PRIMARY), new TrackArtist(ARTIST, ArtistRole.FEATURED)), NOW))
                .isInstanceOf(BadRequestException.class);
    }
}
