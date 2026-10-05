package com.cadence.common.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.util.Locale;

/** Factory for RFC 7807 problems with Cadence conventions. */
public final class Problems {

    public static final String TYPE_PREFIX = "https://cadence.dev/problems/";
    public static final String CODE = "code";

    private Problems() {
    }

    public static ProblemDetail of(HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        return withCode(problem, code);
    }

    public static ProblemDetail withCode(ProblemDetail problem, String code) {
        problem.setType(URI.create(TYPE_PREFIX + code));
        HttpStatus status = HttpStatus.resolve(problem.getStatus());
        if (problem.getTitle() == null && status != null) {
            problem.setTitle(status.getReasonPhrase());
        }
        problem.setProperty(CODE, code);
        return problem;
    }

    /** {@code 404 Not Found} → {@code not-found}. */
    public static String defaultCode(HttpStatusCode status) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved == null ? "error" : resolved.getReasonPhrase().toLowerCase(Locale.ROOT).replace(' ', '-');
    }
}
