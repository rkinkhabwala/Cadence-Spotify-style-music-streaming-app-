package com.cadence.identity.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

/** Request/response records for the identity API. */
final class AuthDtos {

    private AuthDtos() {
    }

    record RegisterRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 10, max = 72) String password,
            @NotBlank @Size(max = 100) String displayName) {
    }

    record LoginRequest(@NotBlank @Email String email, @NotBlank @Size(max = 200) String password) {
    }

    record UpdateProfileRequest(
            @Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String displayName,
            @Size(max = 2048) @URL(regexp = "^(https?://.*)?$", message = "must be an http(s) URL") String avatarUrl,
            @Pattern(regexp = "[A-Z]{2}", message = "must be an ISO 3166-1 alpha-2 code") String country) {
    }

    /** The refresh token is deliberately absent: it is only ever sent as an HttpOnly cookie (D86). */
    record TokenResponse(
            String accessToken,
            @Schema(description = "Access token lifetime in seconds") long expiresIn,
            String tokenType) {
    }
}
