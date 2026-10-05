package com.cadence.identity.application;

import com.cadence.identity.domain.PasswordPolicy;
import com.cadence.identity.domain.User;
import com.cadence.identity.infrastructure.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/** Creates the admin from CADENCE_ADMIN_EMAIL / CADENCE_ADMIN_PASSWORD if absent. Never modifies an existing user. */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final IdentityProperties properties;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate tx;
    private final Clock clock;

    AdminBootstrap(IdentityProperties properties, UserRepository users, PasswordEncoder passwordEncoder,
                   TransactionTemplate tx, Clock clock) {
        this.properties = properties;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrap();
    }

    /** @return {@code true} if an admin was created */
    boolean bootstrap() {
        String email = properties.email();
        if (email == null || email.isBlank()) {
            return false;
        }
        if (properties.password() == null || properties.password().isBlank()) {
            log.warn("CADENCE_ADMIN_EMAIL is set but CADENCE_ADMIN_PASSWORD is not; admin not created");
            return false;
        }
        PasswordPolicy.validate(properties.password());
        String normalized = User.normalizeEmail(email);
        try {
            Boolean created = tx.execute(status -> {
                if (users.existsByEmail(normalized)) {
                    return false;
                }
                users.saveAndFlush(User.bootstrapAdmin(normalized, passwordEncoder.encode(properties.password()),
                        clock.instant()));
                return true;
            });
            if (Boolean.TRUE.equals(created)) {
                log.info("Bootstrapped admin account {}", normalized);
            }
            return Boolean.TRUE.equals(created);
        } catch (DataIntegrityViolationException e) {
            return false; // another instance created it concurrently
        }
    }
}
