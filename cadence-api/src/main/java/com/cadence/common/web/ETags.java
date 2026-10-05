package com.cadence.common.web;

import com.cadence.common.error.CadenceException;
import org.springframework.http.HttpStatus;

/** Strong ETags carrying an entity version ({@code "7"}) for If-Match optimistic locking (spec 5). */
public final class ETags {

    private ETags() {
    }

    public static String of(long version) {
        return "\"" + version + "\"";
    }

    /**
     * @return the version in {@code If-Match}, {@code null} if absent or {@code *}; -1 if unparseable (never matches)
     */
    public static Long parseIfMatch(String header) {
        if (header == null || header.isBlank() || header.strip().equals("*")) {
            return null;
        }
        String value = header.strip();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /** For writes where optimistic locking is mandatory: 428 when {@code If-Match} is missing. */
    public static long requireIfMatch(String header) {
        Long version = parseIfMatch(header);
        if (version == null) {
            throw new CadenceException(HttpStatus.PRECONDITION_REQUIRED, "precondition-required",
                    "Send If-Match with the resource's current ETag");
        }
        return version;
    }
}
