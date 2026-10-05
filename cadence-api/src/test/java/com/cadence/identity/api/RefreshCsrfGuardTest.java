package com.cadence.identity.api;

import com.cadence.common.error.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshCsrfGuardTest {

    private final RefreshCsrfGuard guard = new RefreshCsrfGuard(List.of("http://localhost:5173", " http://localhost:3000 "));

    @Test
    void acceptsNonBrowserClientsSameOriginAndConfiguredOriginsWithTheHeader() {
        assertThatCode(() -> guard.verify(request(null, null))).doesNotThrowAnyException();
        assertThatCode(() -> guard.verify(request("http://localhost:5173", "same-origin"))).doesNotThrowAnyException();
        assertThatCode(() -> guard.verify(request("http://localhost:3000", "same-site"))).doesNotThrowAnyException();
        assertThatCode(() -> guard.verify(request("http://api.cadence.test:8080", "same-origin"))).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingHeaderCrossSiteAndForeignOrigins() {
        MockHttpServletRequest noHeader = request(null, null);
        noHeader.removeHeader(RefreshCsrfGuard.HEADER);

        for (MockHttpServletRequest request : List.of(noHeader, request(null, "cross-site"),
                request("https://evil.example", null), request("null", null), request("http://api.cadence.test", null))) {
            assertThatThrownBy(() -> guard.verify(request)).isInstanceOf(ForbiddenException.class)
                    .satisfies(e -> assertThat(((ForbiddenException) e).code()).isEqualTo("csrf-rejected"));
        }
    }

    private static MockHttpServletRequest request(String origin, String fetchSite) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
        request.setServerName("api.cadence.test");
        request.setServerPort(8080);
        request.addHeader(RefreshCsrfGuard.HEADER, "1");
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        if (fetchSite != null) {
            request.addHeader("Sec-Fetch-Site", fetchSite);
        }
        return request;
    }
}
