package com.cadence.common.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

/** Creates decoders for non-access token types (e.g. playback URLs) that share the Cadence signing key. */
@Component
public class JwtDecoders {

    private final RSAKey signingKey;
    private final JwtProperties properties;

    public JwtDecoders(RSAKey signingKey, JwtProperties properties) {
        this.signingKey = signingKey;
        this.properties = properties;
    }

    public JwtDecoder forTokenUse(String tokenUse) {
        try {
            return JwtConfig.decoder(signingKey, properties.issuer(), tokenUse);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public String issuer() {
        return properties.issuer();
    }
}
