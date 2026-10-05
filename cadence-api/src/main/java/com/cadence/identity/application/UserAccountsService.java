package com.cadence.identity.application;

import com.cadence.common.error.NotFoundException;
import com.cadence.identity.Plan;
import com.cadence.identity.UserAccounts;
import com.cadence.identity.domain.User;
import com.cadence.identity.infrastructure.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.UUID;

@Service
class UserAccountsService implements UserAccounts {

    private final UserRepository users;
    private final Clock clock;

    UserAccountsService(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Plan> planOf(UUID userId) {
        return users.findById(userId).map(User::getPlan);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findIdByEmail(String email) {
        return users.findByEmail(User.normalizeEmail(email)).map(User::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> displayNames(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, User::getDisplayName));
    }

    @Override
    @Transactional
    public void changePlan(UUID userId, Plan plan) {
        users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId))
                .changePlan(plan, clock.instant());
    }
}
