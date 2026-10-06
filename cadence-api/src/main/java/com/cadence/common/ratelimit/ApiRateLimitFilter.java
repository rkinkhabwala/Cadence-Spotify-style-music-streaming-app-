package com.cadence.common.ratelimit;

import com.cadence.common.error.Problems;
import com.cadence.common.error.TooManyRequestsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.regex.Pattern;

/**
 * Global API rate limit (D97): every {@code /api/**} request takes a token from the caller's bucket, keyed by user id
 * when authenticated and by client IP otherwise. Runs in the security chain after authentication. Answers 429 as an
 * RFC 7807 problem with {@code Retry-After}. CORS preflights are not counted. Fails open with the limiter (D23).
 *
 * <p>HLS playlists are not counted: they carry no bearer token (hls.js fetches them with the signed token in the URL),
 * so they would fall into the per-IP bucket, which every listener behind one NAT shares. They can only be obtained
 * through {@code POST /playback}, which is counted per user, and their tokens expire (D97).
 */
public class ApiRateLimitFilter extends OncePerRequestFilter {

    static final String LIMIT = "api";

    private final RateLimiter limiter;
    private final ObjectMapper objectMapper;

    public ApiRateLimitFilter(RateLimiter limiter, ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.objectMapper = objectMapper;
    }

    private static final Pattern HLS_PLAYLIST = Pattern.compile("^/api/v1/playback/[^/]+/(?:[^/]+/index|master)\\.m3u8$");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/") || "OPTIONS".equals(request.getMethod()) || HLS_PLAYLIST.matcher(uri).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            limiter.consume(LIMIT, subject(request));
        } catch (TooManyRequestsException e) {
            ProblemDetail problem = Problems.of(HttpStatus.TOO_MANY_REQUESTS, e.code(), e.getMessage());
            problem.setInstance(URI.create(request.getRequestURI()));
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()));
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), problem);
            return;
        }
        chain.doFilter(request, response);
    }

    private static String subject(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof JwtAuthenticationToken jwt ? "user:" + jwt.getName() : "ip:" + request.getRemoteAddr();
    }
}
