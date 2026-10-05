package com.cadence.identity.api;

import com.cadence.common.security.JwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshCookiesTest {

    private final RefreshCookies cookies = new RefreshCookies(
            new JwtProperties(null, null, "cadence", Duration.ofMinutes(15), Duration.ofDays(30)));

    @Test
    void isSecureEverywhereExceptLoopbackHosts() {
        assertThat(cookies.issue("t", request("cadence.example"))).contains("Secure");
        assertThat(cookies.issue("t", request("192.168.1.20"))).contains("Secure");
        assertThat(cookies.issue("t", request("localhost"))).doesNotContain("Secure");
        assertThat(cookies.issue("t", request("127.0.0.1"))).doesNotContain("Secure");
        assertThat(cookies.issue("t", request("app.localhost"))).doesNotContain("Secure");
    }

    @Test
    void issuesAndClearsAnHttpOnlyStrictCookieScopedToAuth() {
        assertThat(cookies.issue("abc", request("cadence.example")))
                .startsWith("cadence_refresh=abc; Path=/api/v1/auth; Max-Age=2592000;")
                .contains("; HttpOnly", "; SameSite=Strict", "; Secure");
        assertThat(cookies.clear(request("localhost")))
                .startsWith("cadence_refresh=; Path=/api/v1/auth; Max-Age=0;").contains("HttpOnly", "SameSite=Strict");
    }

    private static MockHttpServletRequest request(String host) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setServerName(host);
        return request;
    }
}
