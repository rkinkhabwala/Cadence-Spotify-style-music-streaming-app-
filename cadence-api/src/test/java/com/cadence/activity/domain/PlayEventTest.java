package com.cadence.activity.domain;

import com.cadence.common.error.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlayEventTest {

    private static final Instant T0 = Instant.parse("2026-10-05T12:00:00Z");
    private final UUID user = UUID.randomUUID();
    private final UUID track = UUID.randomUUID();

    private PlayEvent play(int ms) {
        return new PlayEvent(UUID.randomUUID(), user, track, ms, PlaySource.ALBUM, null, false, false, T0);
    }

    @Test
    void firstReportStartsThePlaybackMsPlayedAgo() {
        PlayEvent play = play(30_000);
        assertThat(play.getStartedAt()).isEqualTo(T0.minusSeconds(30));
        assertThat(play.isCounted()).isTrue();
        assertThat(play.getCountedAt()).isEqualTo(T0);
        assertThat(play(29_999).isCounted()).isFalse();
    }

    @Test
    void laterReportsMergeAndCountOnceTheThresholdIsReached() {
        PlayEvent play = play(10_000);
        Instant t1 = T0.plusSeconds(25);

        assertThat(play.report(user, track, 35_000, false, false, t1)).isTrue();
        assertThat(play.getCountedAt()).isEqualTo(t1);
        assertThat(play.report(user, track, 60_000, true, false, t1.plusSeconds(25))).isTrue();
        assertThat(play.getCountedAt()).as("counted only once").isEqualTo(t1);
        assertThat(play.getMsPlayed()).isEqualTo(60_000);
        assertThat(play.isCompleted()).isTrue();
    }

    @Test
    void repeatedOrStaleReportsChangeNothing() {
        PlayEvent play = play(40_000);
        play.report(user, track, 40_000, false, true, T0.plusSeconds(1));

        assertThat(play.report(user, track, 40_000, false, true, T0.plusSeconds(2))).as("same report").isFalse();
        assertThat(play.report(user, track, 5_000, false, false, T0.plusSeconds(3))).as("older report").isFalse();
        assertThat(play.getMsPlayed()).isEqualTo(40_000);
        assertThat(play.isSkipped()).as("flags stick").isTrue();
    }

    @Test
    void aPlayIdCannotBeReusedForAnotherUserOrTrack() {
        PlayEvent play = play(1_000);
        assertThatThrownBy(() -> play.report(UUID.randomUUID(), track, 2_000, false, false, T0))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.code()).isEqualTo("play-mismatch"));
        assertThatThrownBy(() -> play.report(user, UUID.randomUUID(), 2_000, false, false, T0))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void payloadCarriesThePlaybackState() {
        PlayEvent play = play(31_000);
        var payload = play.toPayload();
        assertThat(payload.playId()).isEqualTo(play.getId());
        assertThat(payload.msPlayed()).isEqualTo(31_000);
        assertThat(payload.source()).isEqualTo("ALBUM");
        assertThat(payload.isStream()).isTrue();
    }
}
