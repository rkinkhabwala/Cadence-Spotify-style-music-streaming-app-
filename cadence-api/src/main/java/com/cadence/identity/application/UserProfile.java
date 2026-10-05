package com.cadence.identity.application;

import com.cadence.identity.Plan;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/** Read model of the current user, returned by {@code GET/PATCH /me}. */
public record UserProfile(
        UUID id,
        String email,
        String displayName,
        String avatarUrl,
        String country,
        LocalDate birthDate,
        Plan plan,
        Set<String> roles,
        Instant createdAt) {
}
