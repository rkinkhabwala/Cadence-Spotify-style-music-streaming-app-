package com.cadence.activity.domain;

import com.cadence.common.error.BadRequestException;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/** Time ranges of {@code /me/top/tracks} (spec 5): 4 weeks, 6 months, all time. */
public enum TopRange {
    SHORT(Duration.ofDays(28)),
    MEDIUM(Duration.ofDays(182)),
    LONG(null);

    private final Duration window;

    TopRange(Duration window) {
        this.window = window;
    }

    /** Earliest counted play included at {@code now}. */
    public Instant since(Instant now) {
        return window == null ? Instant.EPOCH : now.minus(window);
    }

    /** Defaults to {@link #MEDIUM}. @throws BadRequestException ({@code invalid-range}) for unknown values */
    public static TopRange parse(String value) {
        if (value == null || value.isBlank()) {
            return MEDIUM;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid-range", "range must be short, medium or long");
        }
    }
}
