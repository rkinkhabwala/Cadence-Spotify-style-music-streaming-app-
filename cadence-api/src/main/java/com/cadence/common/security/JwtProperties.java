package com.cadence.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.time.Duration;

/**
 * RS256 signing configuration. Key files are PEM: PKCS#8 private key and X.509 public key
 * (generate dev keys with {@code scripts/generate-dev-keys.sh}).
 */
@ConfigurationProperties("cadence.security.jwt")
public record JwtProperties(
        Resource privateKeyLocation,
        Resource publicKeyLocation,
        String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl) {
}
