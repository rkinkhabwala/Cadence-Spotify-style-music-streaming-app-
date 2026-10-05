package com.cadence.identity.application;

import com.cadence.common.error.NotFoundException;
import com.cadence.identity.domain.User;
import com.cadence.identity.infrastructure.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class ProfileService {

    private final UserRepository users;
    private final ProfileMapper mapper;
    private final Clock clock;

    ProfileService(UserRepository users, ProfileMapper mapper, Clock clock) {
        this.users = users;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public UserProfile me(UUID userId) {
        return mapper.toProfile(load(userId));
    }

    /** {@code null} fields are left unchanged; idempotent. */
    @Transactional
    public UserProfile update(UUID userId, String displayName, String avatarUrl, String country) {
        User user = load(userId);
        user.updateProfile(displayName, avatarUrl, country, clock.instant());
        return mapper.toProfile(user);
    }

    private User load(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
    }
}
