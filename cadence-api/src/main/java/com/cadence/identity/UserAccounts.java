package com.cadence.identity;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Public identity API for other bounded contexts. */
public interface UserAccounts {

    /** Plan of an existing user, or empty if the user does not exist. */
    Optional<Plan> planOf(UUID userId);

    /** Id of the user with this email (case-insensitive), if registered. */
    Optional<UUID> findIdByEmail(String email);

    /** Display names keyed by user id; unknown ids are absent. */
    Map<UUID, String> displayNames(Collection<UUID> userIds);

    /** Upgrades or downgrades a user (admin/seed/test use; there is no billing). */
    void changePlan(UUID userId, Plan plan);
}
