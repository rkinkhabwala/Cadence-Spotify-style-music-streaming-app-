package com.cadence.activity.domain;

import com.cadence.common.error.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopRangeTest {

    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");

    @Test
    void rangesAreFourWeeksSixMonthsAndAllTime() {
        assertThat(TopRange.parse("short").since(NOW)).isEqualTo(Instant.parse("2026-09-07T00:00:00Z"));
        assertThat(TopRange.parse("MEDIUM").since(NOW)).isEqualTo(Instant.parse("2026-04-06T00:00:00Z"));
        assertThat(TopRange.parse("long").since(NOW)).isEqualTo(Instant.EPOCH);
        assertThat(TopRange.parse(null)).isEqualTo(TopRange.MEDIUM);
    }

    @Test
    void unknownRangeIsABadRequest() {
        assertThatThrownBy(() -> TopRange.parse("decade"))
                .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.code()).isEqualTo("invalid-range"));
    }
}
