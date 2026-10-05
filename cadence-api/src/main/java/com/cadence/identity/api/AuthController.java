package com.cadence.identity.api;

import com.cadence.common.ratelimit.RateLimiter;
import com.cadence.common.web.ApiPaths;
import com.cadence.identity.api.AuthDtos.LoginRequest;
import com.cadence.identity.api.AuthDtos.RefreshTokenRequest;
import com.cadence.identity.api.AuthDtos.RegisterRequest;
import com.cadence.identity.api.AuthDtos.TokenResponse;
import com.cadence.identity.application.AuthService;
import com.cadence.identity.application.AuthTokens;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping(ApiPaths.V1 + "/auth")
@Tag(name = "Auth")
class AuthController {

    private final AuthService auth;
    private final RateLimiter rateLimiter;

    AuthController(AuthService auth, RateLimiter rateLimiter) {
        this.auth = auth;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/register")
    @Operation(summary = "Register a LISTENER account and sign in",
            description = "Not idempotent: registering an existing email returns 409.")
    ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthTokens tokens = auth.register(request.email(), request.password(), request.displayName());
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/me")).body(toResponse(tokens));
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in", description = "Rate limited to 5 attempts per minute per client IP. "
            + "Not idempotent: each call starts a new session.")
    TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        rateLimiter.consume("login", http.getRemoteAddr());
        return toResponse(auth.login(request.email(), request.password()));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate a refresh token", description = "Each refresh token is single-use. "
            + "Reusing a rotated token revokes the whole session.")
    TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return toResponse(auth.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke the session of a refresh token (idempotent)")
    void logout(@Valid @RequestBody RefreshTokenRequest request) {
        auth.logout(request.refreshToken());
    }

    private static TokenResponse toResponse(AuthTokens tokens) {
        return new TokenResponse(tokens.accessToken(), tokens.refreshToken(), tokens.expiresInSeconds(), "Bearer");
    }
}
