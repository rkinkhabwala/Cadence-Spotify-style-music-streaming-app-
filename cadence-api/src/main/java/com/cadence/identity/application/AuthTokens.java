package com.cadence.identity.application;

/** A freshly issued access/refresh token pair. */
public record AuthTokens(String accessToken, String refreshToken, long expiresInSeconds) {
}
