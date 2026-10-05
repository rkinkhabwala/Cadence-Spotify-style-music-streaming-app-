package com.cadence.identity.api;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/** Public signing keys so other services can validate Cadence JWTs (spec 6). */
@RestController
@Tag(name = "Auth")
class JwksController {

    private final Map<String, Object> publicJwks;

    JwksController(RSAKey cadenceSigningKey) {
        this.publicJwks = new JWKSet(cadenceSigningKey.toPublicJWK()).toJSONObject();
    }

    @GetMapping(value = "/.well-known/jwks.json", produces = "application/json")
    @Operation(summary = "JSON Web Key Set with the RS256 public key")
    ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()).body(publicJwks);
    }
}
