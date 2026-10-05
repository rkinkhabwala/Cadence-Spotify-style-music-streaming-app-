package com.cadence.identity.api;

import com.cadence.common.error.UnauthorizedException;
import com.cadence.common.ratelimit.RateLimiter;
import com.cadence.common.web.ApiPaths;
import com.cadence.identity.api.AuthDtos.LoginRequest;
import com.cadence.identity.api.AuthDtos.RegisterRequest;
import com.cadence.identity.api.AuthDtos.TokenResponse;
import com.cadence.identity.application.AuthService;
import com.cadence.identity.application.AuthTokens;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Sign-in endpoints. The access token is returned in the body; the refresh token only ever travels in the
 * {@code HttpOnly} {@value RefreshCookies#NAME} cookie (D86) and never appears in a response body.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/auth")
@Tag(name = "Auth")
class AuthController {

    private final AuthService auth;
    private final RateLimiter rateLimiter;
    private final RefreshCookies cookies;
    private final RefreshCsrfGuard csrf;

    AuthController(AuthService auth, RateLimiter rateLimiter, RefreshCookies cookies, RefreshCsrfGuard csrf) {
        this.auth = auth;
        this.rateLimiter = rateLimiter;
        this.cookies = cookies;
        this.csrf = csrf;
    }

    @PostMapping("/register")
    @Operation(summary = "Register a LISTENER account and sign in",
            description = "Sets the refresh-token cookie. Not idempotent: registering an existing email returns 409.")
    ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        AuthTokens tokens = auth.register(request.email(), request.password(), request.displayName());
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/me"))
                .header(HttpHeaders.SET_COOKIE, cookies.issue(tokens.refreshToken(), http))
                .body(toResponse(tokens));
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in", description = "Sets the refresh-token cookie. Rate limited to 5 attempts per "
            + "minute per client IP. Not idempotent: each call starts a new session.")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        rateLimiter.consume("login", http.getRemoteAddr());
        AuthTokens tokens = auth.login(request.email(), request.password());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.issue(tokens.refreshToken(), http))
                .body(toResponse(tokens));
    }

    @PostMapping("/refresh")
    @Parameter(name = RefreshCsrfGuard.HEADER, in = ParameterIn.HEADER, required = true, example = "1")
    @Operation(summary = "Rotate the refresh-token cookie and get a new access token",
            description = "Reads the refresh token from the cookie. Each refresh token is single-use: reusing a "
                    + "rotated one revokes the whole session. Not idempotent, by design.")
    ResponseEntity<TokenResponse> refresh(HttpServletRequest http, HttpServletResponse response) {
        csrf.verify(http);
        String refreshToken = cookies.read(http).orElseThrow(() -> rejectedRefresh(http, response));
        AuthTokens tokens;
        try {
            tokens = auth.refresh(refreshToken);
        } catch (UnauthorizedException e) {
            response.addHeader(HttpHeaders.SET_COOKIE, cookies.clear(http));
            throw e;
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.issue(tokens.refreshToken(), http))
                .body(toResponse(tokens));
    }

    @PostMapping("/logout")
    @Parameter(name = RefreshCsrfGuard.HEADER, in = ParameterIn.HEADER, required = true, example = "1")
    @Operation(summary = "Revoke the session of the refresh-token cookie and clear it (idempotent)")
    ResponseEntity<Void> logout(HttpServletRequest http) {
        csrf.verify(http);
        cookies.read(http).ifPresent(auth::logout);
        return ResponseEntity.status(HttpStatus.NO_CONTENT)
                .header(HttpHeaders.SET_COOKIE, cookies.clear(http))
                .build();
    }

    private UnauthorizedException rejectedRefresh(HttpServletRequest http, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.clear(http));
        return new UnauthorizedException("invalid-refresh-token", "No refresh-token cookie was sent");
    }

    private static TokenResponse toResponse(AuthTokens tokens) {
        return new TokenResponse(tokens.accessToken(), tokens.expiresInSeconds(), "Bearer");
    }
}
