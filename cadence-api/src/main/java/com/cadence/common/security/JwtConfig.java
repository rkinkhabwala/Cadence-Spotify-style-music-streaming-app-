package com.cadence.common.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

@Configuration(proxyBeanMethods = false)
class JwtConfig {

    /** Signing key; {@code kid} is the RFC 7638 thumbprint so it changes whenever the key does. */
    @Bean
    RSAKey cadenceSigningKey(JwtProperties properties) throws JOSEException {
        RSAPublicKey publicKey = PemKeys.publicKey(properties.publicKeyLocation());
        RSAPrivateKey privateKey = PemKeys.privateKey(properties.privateKeyLocation());
        if (!publicKey.getModulus().equals(privateKey.getModulus())) {
            throw new IllegalStateException("JWT public and private keys are not a pair");
        }
        RSAKey key = new RSAKey.Builder(publicKey).privateKey(privateKey)
                .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
        return new RSAKey.Builder(key).keyID(key.computeThumbprint().toString()).build();
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey cadenceSigningKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(cadenceSigningKey)));
    }

    /** Decoder for API access tokens: RS256, issuer, expiry, and {@code token_use=access}. */
    @Bean
    JwtDecoder jwtDecoder(RSAKey cadenceSigningKey, JwtProperties properties) throws JOSEException {
        return decoder(cadenceSigningKey, properties.issuer(), TokenUse.ACCESS);
    }

    /** Builds a decoder that only accepts tokens minted for {@code tokenUse}. */
    static NimbusJwtDecoder decoder(RSAKey key, String issuer, String tokenUse) throws JOSEException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> use = jwt -> tokenUse.equals(jwt.getClaimAsString(TokenUse.CLAIM))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong token_use", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), use));
        return decoder;
    }
}
