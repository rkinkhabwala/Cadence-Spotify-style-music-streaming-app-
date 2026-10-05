package com.cadence.identity.domain;

import com.cadence.common.error.BadRequestException;

import java.nio.charset.StandardCharsets;

/** Spec 6: at least 10 characters. BCrypt only uses the first 72 bytes, so longer passwords are rejected. */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    public static void validate(String password) {
        if (password == null || password.codePointCount(0, password.length()) < MIN_LENGTH) {
            throw new BadRequestException("weak-password", "Password must be at least " + MIN_LENGTH + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new BadRequestException("password-too-long", "Password must be at most " + MAX_BYTES + " bytes");
        }
    }
}
