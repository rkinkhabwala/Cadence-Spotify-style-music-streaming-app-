package com.cadence.common.error;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Base for every expected business error. Rendered as an RFC 7807 problem by {@link GlobalExceptionHandler}
 * with {@code type = https://cadence.dev/problems/{code}} and a {@code code} property.
 */
public class CadenceException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public CadenceException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** Extra response headers (e.g. {@code Retry-After}, {@code Content-Range}). */
    public Map<String, String> headers() {
        return Map.of();
    }
}
