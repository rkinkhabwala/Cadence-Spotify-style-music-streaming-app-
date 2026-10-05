package com.cadence.common.security;

/**
 * Value of the {@code token_use} claim. All Cadence JWTs share one signing key, so every decoder checks this
 * claim to make sure a token minted for one purpose (e.g. a playback URL) is never accepted for another.
 */
public final class TokenUse {

    public static final String CLAIM = "token_use";
    public static final String ACCESS = "access";
    public static final String PLAYBACK = "playback";

    private TokenUse() {
    }
}
