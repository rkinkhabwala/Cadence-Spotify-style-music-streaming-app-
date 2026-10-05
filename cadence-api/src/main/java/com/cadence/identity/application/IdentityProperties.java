package com.cadence.identity.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Admin bootstrap (spec 4): created at startup when both values are set and the email is not registered. */
@ConfigurationProperties("cadence.admin")
record IdentityProperties(String email, String password) {
}
