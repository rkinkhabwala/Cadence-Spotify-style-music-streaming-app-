package com.cadence.common.error;

import org.springframework.http.HttpStatus;

/** 429 with a {@code Retry-After} header (seconds). */
public class TooManyRequestsException extends CadenceException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(String code, String detail, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, code, detail);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
