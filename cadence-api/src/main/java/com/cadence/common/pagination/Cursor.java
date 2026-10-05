package com.cadence.common.pagination;

import com.cadence.common.error.BadRequestException;
import com.cadence.events.CadenceJackson;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Opaque keyset cursor: the sort-key values of the last item on a page, encoded as base64url JSON.
 * Example: {@code Cursor.of(likedAt, trackId)} → {@code WyIyMDI2LTEw...}.
 */
public record Cursor(List<String> values) {

    private static final ObjectMapper MAPPER = CadenceJackson.newObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    public Cursor {
        values = List.copyOf(values);
    }

    public static Cursor of(Object... values) {
        return new Cursor(Arrays.stream(values).map(v -> Objects.toString(v, null)).toList());
    }

    public String encode() {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MAPPER.writeValueAsBytes(values));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encode cursor", e);
        }
    }

    /** @throws BadRequestException ({@code invalid-cursor}) if the value was not produced by {@link #encode()} */
    public static Cursor decode(String encoded) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(encoded);
            return new Cursor(MAPPER.readValue(new String(json, StandardCharsets.UTF_8), STRING_LIST));
        } catch (Exception e) {
            throw invalid();
        }
    }

    public String string(int index) {
        if (index < 0 || index >= values.size()) {
            throw invalid();
        }
        return values.get(index);
    }

    public UUID uuid(int index) {
        try {
            return UUID.fromString(string(index));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw invalid();
        }
    }

    public Instant instant(int index) {
        try {
            return Instant.parse(string(index));
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    public long longValue(int index) {
        try {
            return Long.parseLong(string(index));
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    private static BadRequestException invalid() {
        return new BadRequestException("invalid-cursor", "The cursor is malformed or expired");
    }
}
