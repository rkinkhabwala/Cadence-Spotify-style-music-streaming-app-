package com.cadence.events;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class UuidV7Test {

    @Test
    void hasVersion7AndRfcVariant() {
        UUID id = UuidV7.generate();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void encodesTheCurrentTime() {
        Instant before = Instant.now();
        UUID id = UuidV7.generate();

        assertThat(UuidV7.timestampOf(id)).isCloseTo(before, within(java.time.Duration.ofSeconds(1)));
    }

    @Test
    void isStrictlyIncreasingAndUnique() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            ids.add(UuidV7.generate());
        }

        assertThat(new HashSet<>(ids)).hasSize(ids.size());
        assertThat(ids).isSorted();
    }

    @Test
    void staysMonotonicWhenTheClockGoesBackwards() {
        UuidV7 generator = new UuidV7();
        UUID later = generator.next(4_000_000_000_000L);
        UUID earlierClock = generator.next(1_000_000_000_000L);

        assertThat(earlierClock).isGreaterThan(later);
    }

    @Test
    void timestampOfRejectsOtherVersions() {
        assertThatThrownBy(() -> UuidV7.timestampOf(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
