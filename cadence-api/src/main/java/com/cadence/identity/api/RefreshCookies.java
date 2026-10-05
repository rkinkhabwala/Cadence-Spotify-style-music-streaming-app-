package com.cadence.identity.api;

import com.cadence.common.security.JwtProperties;
import com.cadence.common.web.ApiPaths;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The refresh token travels only in this cookie (D86): {@code HttpOnly} so scripts can't read it, {@code SameSite=Strict}
 * so no other site can make the browser send it, and scoped to {@code /api/v1/auth} so ordinary API calls never carry
 * it. {@code Secure} is set unless the request host is loopback, because browsers don't all keep Secure cookies on
 * plain-http localhost.
 */
@Component
class RefreshCookies {

    static final String NAME = "cadence_refresh";
    static final String PATH = ApiPaths.V1 + "/auth";

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final Duration maxAge;

    RefreshCookies(JwtProperties jwt) {
        this.maxAge = jwt.refreshTokenTtl();
    }

    String issue(String refreshToken, HttpServletRequest request) {
        return build(refreshToken, maxAge, request);
    }

    String clear(HttpServletRequest request) {
        return build("", Duration.ZERO, request);
    }

    Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(c -> NAME.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank())
                .map(Cookie::getValue)
                .findFirst();
    }

    static boolean secure(HttpServletRequest request) {
        String host = request.getServerName().toLowerCase(Locale.ROOT);
        return !(LOOPBACK_HOSTS.contains(host) || host.endsWith(".localhost"));
    }

    private static String build(String value, Duration maxAge, HttpServletRequest request) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure(request))
                .sameSite("Strict")
                .path(PATH)
                .maxAge(maxAge)
                .build()
                .toString();
    }
}
