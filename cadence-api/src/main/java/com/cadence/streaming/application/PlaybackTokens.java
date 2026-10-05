package com.cadence.streaming.application;

import com.cadence.common.error.UnauthorizedException;
import com.cadence.common.security.JwtDecoders;
import com.cadence.common.security.TokenUse;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Signed playback grants carried in manifest URLs (hls.js can't add Authorization headers to every request).
 * {@code token_use=playback}, so they are never accepted as API access tokens and vice versa.
 */
@Component
class PlaybackTokens {

    static final String MASTER = "master";
    static final String MEDIA = "media";

    record Grant(UUID userId, UUID trackId, int maxKbps, int durationMs, String scope, Instant expiresAt) {
    }

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final String issuer;

    PlaybackTokens(JwtEncoder encoder, JwtDecoders decoders) {
        this.encoder = encoder;
        this.decoder = decoders.forTokenUse(TokenUse.PLAYBACK);
        this.issuer = decoders.issuer();
    }

    String mint(Grant grant, Instant now) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(grant.userId().toString())
                .issuedAt(now)
                .expiresAt(grant.expiresAt())
                .claim(TokenUse.CLAIM, TokenUse.PLAYBACK)
                .claim("trk", grant.trackId().toString())
                .claim("mbr", grant.maxKbps())
                .claim("dur", grant.durationMs())
                .claim("scp", grant.scope())
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

    /** @throws UnauthorizedException if the token is invalid, expired, for another track or of another scope */
    Grant verify(String token, String scope, UUID trackId) {
        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtException | IllegalArgumentException e) {
            throw invalid();
        }
        if (!scope.equals(jwt.getClaimAsString("scp")) || !trackId.toString().equals(jwt.getClaimAsString("trk"))) {
            throw invalid();
        }
        return new Grant(UUID.fromString(jwt.getSubject()), trackId, ((Number) jwt.getClaim("mbr")).intValue(),
                ((Number) jwt.getClaim("dur")).intValue(), scope, jwt.getExpiresAt());
    }

    private static UnauthorizedException invalid() {
        return new UnauthorizedException("invalid-token", "The playback URL is invalid or has expired; start playback again");
    }
}
