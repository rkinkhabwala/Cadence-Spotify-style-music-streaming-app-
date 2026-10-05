package com.cadence.identity;

import com.cadence.IntegrationTest;
import com.cadence.common.security.TokenUse;
import com.cadence.support.ApiClient;
import com.cadence.support.ApiClient.Session;
import com.cadence.support.TestKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthIT extends IntegrationTest {

    @Autowired
    JwtEncoder jwtEncoder;

    // ---------------------------------------------------------------- register

    @Test
    void registerReturnsTokensAndAListenerProfile() {
        String email = "New.User-" + UUID.randomUUID() + "@Test.dev";
        ResponseEntity<JsonNode> response = api.post("/api/v1/auth/register",
                Map.of("email", email, "password", ApiClient.PASSWORD, "displayName", "  Ada  "), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).hasToString("/api/v1/me");
        JsonNode body = response.getBody();
        assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.get("expiresIn").asLong()).isEqualTo(900);
        assertThat(body.get("refreshToken").asText()).hasSizeGreaterThan(40);

        JsonNode me = api.get("/api/v1/me", body.get("accessToken").asText()).getBody();
        assertThat(me.get("email").asText()).isEqualTo(email.toLowerCase());
        assertThat(me.get("displayName").asText()).isEqualTo("Ada");
        assertThat(me.get("plan").asText()).isEqualTo("FREE");
        assertThat(me.get("roles")).extracting(JsonNode::asText).containsExactly("LISTENER");
        assertThat(me.has("passwordHash")).isFalse();
    }

    @Test
    void registerRejectsInvalidInput() {
        ResponseEntity<JsonNode> response = api.post("/api/v1/auth/register",
                Map.of("email", "not-an-email", "password", "short", "displayName", ""), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("validation-failed");
        assertThat(response.getBody().get("errors")).extracting(e -> e.get("field").asText())
                .contains("email", "password", "displayName");
    }

    @Test
    void registerRejectsDuplicateEmailCaseInsensitively() {
        Session existing = api.register();

        ResponseEntity<JsonNode> response = api.post("/api/v1/auth/register",
                Map.of("email", existing.email().toUpperCase(), "password", ApiClient.PASSWORD, "displayName", "X"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText()).isEqualTo("email-taken");
    }

    // ---------------------------------------------------------------- login

    @Test
    void loginWithCorrectAndWrongPasswords() {
        Session user = api.register();

        Session loggedIn = api.login(user.email(), ApiClient.PASSWORD);
        ResponseEntity<JsonNode> wrong = api.loginFrom(ApiClient.randomIp(), user.email(), "wrong-password-123");
        ResponseEntity<JsonNode> unknown = api.loginFrom(ApiClient.randomIp(), "nobody-" + UUID.randomUUID() + "@x.dev",
                ApiClient.PASSWORD);

        assertThat(loggedIn.userId()).isEqualTo(user.userId());
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrong.getBody().get("code").asText()).isEqualTo("invalid-credentials");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknown.getBody().get("detail")).isEqualTo(wrong.getBody().get("detail"));
    }

    @Test
    void loginIsRateLimitedToFivePerMinutePerIp() {
        Session user = api.register();
        String ip = ApiClient.randomIp();

        for (int i = 0; i < 5; i++) {
            assertThat(api.loginFrom(ip, user.email(), "wrong-password-123").getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        ResponseEntity<JsonNode> sixth = api.loginFrom(ip, user.email(), ApiClient.PASSWORD);
        ResponseEntity<JsonNode> otherIp = api.loginFrom(ApiClient.randomIp(), user.email(), ApiClient.PASSWORD);

        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(sixth.getBody().get("code").asText()).isEqualTo("rate-limited");
        assertThat(Long.parseLong(sixth.getHeaders().getFirst("Retry-After"))).isBetween(1L, 60L);
        assertThat(otherIp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------- refresh

    @Test
    void fullLifecycleRegisterLoginRefreshLogout() {
        Session user = api.register();
        Session login = api.login(user.email(), ApiClient.PASSWORD);

        JsonNode refreshed = refresh(login.refreshToken());
        assertThat(refreshed.get("refreshToken").asText()).isNotEqualTo(login.refreshToken());
        assertThat(api.get("/api/v1/me", refreshed.get("accessToken").asText()).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(api.post("/api/v1/auth/logout", Map.of("refreshToken", refreshed.get("refreshToken").asText()), null)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.post("/api/v1/auth/logout", Map.of("refreshToken", refreshed.get("refreshToken").asText()), null)
                .getStatusCode()).as("logout is idempotent").isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<JsonNode> afterLogout = api.post("/api/v1/auth/refresh",
                Map.of("refreshToken", refreshed.get("refreshToken").asText()), null);
        assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(afterLogout.getBody().get("code").asText()).isEqualTo("invalid-refresh-token");
        // the session from register() is a different family and still works
        assertThat(api.post("/api/v1/auth/refresh", Map.of("refreshToken", user.refreshToken()), null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void expiredAccessTokenWithValidRefreshTokenYieldsANewPair() {
        Session user = api.register();
        String expired = accessToken(user.userId(), Instant.now().minus(20, ChronoUnit.MINUTES), jwtEncoder, TokenUse.ACCESS);

        ResponseEntity<JsonNode> rejected = api.get("/api/v1/me", expired);
        JsonNode pair = refresh(user.refreshToken());

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rejected.getBody().get("code").asText()).isEqualTo("invalid-token");
        assertThat(rejected.getHeaders().getFirst("WWW-Authenticate")).contains("invalid_token");
        assertThat(api.get("/api/v1/me", pair.get("accessToken").asText()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void reusingARotatedRefreshTokenRevokesTheWholeFamily() {
        Session user = api.register();
        JsonNode second = refresh(user.refreshToken());

        ResponseEntity<JsonNode> reuse = api.post("/api/v1/auth/refresh", Map.of("refreshToken", user.refreshToken()), null);
        ResponseEntity<JsonNode> successorAfterReuse = api.post("/api/v1/auth/refresh",
                Map.of("refreshToken", second.get("refreshToken").asText()), null);

        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reuse.getBody().get("code").asText()).isEqualTo("refresh-token-reused");
        assertThat(successorAfterReuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(successorAfterReuse.getBody().get("code").asText()).isEqualTo("invalid-refresh-token");
    }

    @Test
    void unknownRefreshTokenIsRejected() {
        ResponseEntity<JsonNode> response = api.post("/api/v1/auth/refresh", Map.of("refreshToken", "nope"), null);
        ResponseEntity<JsonNode> blank = api.post("/api/v1/auth/refresh", Map.of("refreshToken", ""), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------------------------------------------------------------- /me

    @Test
    void patchMeUpdatesOnlyProvidedFields() {
        Session user = api.register();

        ResponseEntity<JsonNode> updated = api.patch("/api/v1/me",
                Map.of("country", "DE", "avatarUrl", "https://img.example/a.png"), user.accessToken());
        ResponseEntity<JsonNode> renamed = api.patch("/api/v1/me", Map.of("displayName", "Grace"), user.accessToken());

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody().get("displayName").asText()).isEqualTo("Grace");
        assertThat(renamed.getBody().get("country").asText()).isEqualTo("DE");
        assertThat(renamed.getBody().get("avatarUrl").asText()).isEqualTo("https://img.example/a.png");
    }

    @Test
    void patchMeValidatesInput() {
        Session user = api.register();

        ResponseEntity<JsonNode> response = api.patch("/api/v1/me",
                Map.of("country", "Germany", "avatarUrl", "javascript:alert(1)", "displayName", "   "), user.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("errors")).extracting(e -> e.get("field").asText())
                .contains("country", "avatarUrl", "displayName");
    }

    // ---------------------------------------------------------------- 401 / 403

    @Test
    void meRequiresAValidAccessToken() throws Exception {
        Session user = api.register();
        KeyPair foreign = TestKeys.generate();
        JwtEncoder foreignEncoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(
                new RSAKey.Builder((RSAPublicKey) foreign.getPublic()).privateKey((RSAPrivateKey) foreign.getPrivate()).build())));

        ResponseEntity<JsonNode> none = api.get("/api/v1/me", null);
        ResponseEntity<JsonNode> garbage = api.get("/api/v1/me", "not.a.jwt");
        ResponseEntity<JsonNode> wrongKey = api.get("/api/v1/me", accessToken(user.userId(), Instant.now(), foreignEncoder, TokenUse.ACCESS));
        ResponseEntity<JsonNode> wrongUse = api.get("/api/v1/me", accessToken(user.userId(), Instant.now(), jwtEncoder, TokenUse.PLAYBACK));

        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(none.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(none.getBody().get("code").asText()).isEqualTo("unauthorized");
        assertThat(List.of(garbage, wrongKey, wrongUse)).allSatisfy(r -> {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(r.getBody().get("code").asText()).isEqualTo("invalid-token");
        });
    }

    @Test
    void listenerGets403OnAdminRoutesAndAdminPassesTheGate() {
        Session listener = api.register();
        Session admin = api.admin();

        ResponseEntity<JsonNode> asListener = api.get("/api/v1/admin/anything", listener.accessToken());
        ResponseEntity<JsonNode> asAnonymous = api.get("/api/v1/admin/anything", null);
        ResponseEntity<JsonNode> asAdmin = api.get("/api/v1/admin/anything", admin.accessToken());

        assertThat(asListener.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(asListener.getBody().get("code").asText()).isEqualTo("forbidden");
        assertThat(asAnonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(asAdmin.getStatusCode()).as("authorized, but no such route").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------- JWKS

    @Test
    void accessTokensVerifyAgainstThePublishedJwks() {
        Session user = api.register();

        ResponseEntity<JsonNode> jwks = api.get("/.well-known/jwks.json", null);
        Jwt jwt = NimbusJwtDecoder.withJwkSetUri(http.getRootUri() + "/.well-known/jwks.json").build()
                .decode(user.accessToken());

        assertThat(jwks.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode key = jwks.getBody().get("keys").get(0);
        assertThat(key.get("kty").asText()).isEqualTo("RSA");
        assertThat(key.get("alg").asText()).isEqualTo("RS256");
        assertThat(key.has("d")).as("private exponent must never be published").isFalse();
        assertThat(jwt.getHeaders().get("kid")).isEqualTo(key.get("kid").asText());
        assertThat(jwt.getSubject()).isEqualTo(user.userId().toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("LISTENER");
        assertThat(jwt.getExpiresAt()).isBetween(Instant.now().plusSeconds(800), Instant.now().plusSeconds(901));
    }

    private JsonNode refresh(String refreshToken) {
        ResponseEntity<JsonNode> response = api.post("/api/v1/auth/refresh", Map.of("refreshToken", refreshToken), null);
        assertThat(response.getStatusCode()).as("refresh: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    static String accessToken(UUID userId, Instant issuedAt, JwtEncoder encoder, String tokenUse) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("cadence")
                .subject(userId.toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(15, ChronoUnit.MINUTES))
                .claim(TokenUse.CLAIM, tokenUse)
                .claim("roles", List.of("LISTENER"))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }
}
