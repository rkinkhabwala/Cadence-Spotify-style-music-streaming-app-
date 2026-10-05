package com.cadence.identity.api;

import com.cadence.common.error.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * CSRF defence for the endpoints that act on the refresh cookie (refresh, logout); see D86. {@code SameSite=Strict}
 * already stops other sites from sending the cookie. On top of that:
 * <ol>
 *   <li>A custom header ({@value #HEADER}) is required. HTML forms can't set headers, and a cross-origin script
 *       that sets one triggers a CORS preflight, which only the configured web origins pass.</li>
 *   <li>Browsers that report {@code Sec-Fetch-Site: cross-site} are refused.</li>
 *   <li>If the browser sends {@code Origin}, it must be the API's own origin or one of the configured web origins.</li>
 * </ol>
 * Non-browser clients send neither {@code Origin} nor {@code Sec-Fetch-Site}, so for them only the header is checked.
 */
@Component
class RefreshCsrfGuard {

    static final String HEADER = "X-Cadence-CSRF";

    private final List<String> allowedOrigins;

    RefreshCsrfGuard(@Value("${cadence.web.allowed-origins}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins.stream().map(String::strip).filter(o -> !o.isEmpty()).toList();
    }

    void verify(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            throw rejected("Missing " + HEADER + " header");
        }
        if ("cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))) {
            throw rejected("Cross-site request");
        }
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !allowedOrigins.contains(origin) && !origin.equalsIgnoreCase(ownOrigin(request))) {
            throw rejected("Origin not allowed");
        }
    }

    private static String ownOrigin(HttpServletRequest request) {
        String scheme = request.getScheme().toLowerCase(Locale.ROOT);
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
    }

    private static ForbiddenException rejected(String detail) {
        return new ForbiddenException("csrf-rejected", detail);
    }
}
