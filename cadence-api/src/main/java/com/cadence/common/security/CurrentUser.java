package com.cadence.common.security;

import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller, resolved from the access token. Declare it as a controller method parameter.
 *
 * @param id    user id (JWT {@code sub})
 * @param roles role names without the {@code ROLE_} prefix, e.g. {@code LISTENER}, {@code ADMIN}
 */
public record CurrentUser(UUID id, Set<String> roles) {

    public boolean isAdmin() {
        return roles.contains("ADMIN");
    }
}
