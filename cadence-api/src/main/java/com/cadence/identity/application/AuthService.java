package com.cadence.identity.application;

import com.cadence.common.error.ConflictException;
import com.cadence.common.error.UnauthorizedException;
import com.cadence.common.security.JwtProperties;
import com.cadence.identity.domain.PasswordPolicy;
import com.cadence.identity.domain.RefreshToken;
import com.cadence.identity.domain.User;
import com.cadence.identity.infrastructure.RefreshTokenRepository;
import com.cadence.identity.infrastructure.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final JwtProperties jwtProperties;
    private final Clock clock;
    /** Compared against when the email is unknown, so response time doesn't reveal registered emails. */
    private final String dummyHash;

    AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                TokenService tokens, JwtProperties jwtProperties, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("timing-equalizer-password");
    }

    /** Not idempotent by design: a second registration with the same email is a 409. */
    @Transactional
    public AuthTokens register(String email, String password, String displayName) {
        PasswordPolicy.validate(password);
        String normalized = User.normalizeEmail(email);
        if (users.existsByEmail(normalized)) {
            throw emailTaken();
        }
        Instant now = clock.instant();
        User user = User.registerListener(normalized, passwordEncoder.encode(password), displayName, now);
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw emailTaken(); // concurrent registration with the same email
        }
        return issue(user, now);
    }

    @Transactional
    public AuthTokens login(String email, String password) {
        Optional<User> user = users.findByEmail(User.normalizeEmail(email));
        String hash = user.map(User::getPasswordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(password, hash);
        if (user.isEmpty() || !matches) {
            throw new UnauthorizedException("invalid-credentials", "Email or password is incorrect");
        }
        return issue(user.get(), clock.instant());
    }

    /**
     * Rotates a refresh token. Presenting an already-rotated token revokes its whole family; that revocation must
     * survive the 401, hence {@code noRollbackFor}.
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public AuthTokens refresh(String refreshToken) {
        Instant now = clock.instant();
        RefreshToken current = refreshTokens.findByHashForUpdate(TokenService.hash(refreshToken))
                .orElseThrow(AuthService::invalidRefreshToken);
        if (current.isReuse()) {
            int revoked = refreshTokens.revokeFamily(current.getFamilyId(), now);
            log.warn("Refresh token reuse detected for user {}; revoked {} token(s) in family {}",
                    current.getUserId(), revoked, current.getFamilyId());
            throw new UnauthorizedException("refresh-token-reused",
                    "This refresh token was already used; the session has been revoked");
        }
        if (!current.isUsable(now)) {
            throw invalidRefreshToken();
        }
        User user = users.findById(current.getUserId()).orElseThrow(AuthService::invalidRefreshToken);
        String raw = tokens.newRefreshToken();
        refreshTokens.save(current.rotate(TokenService.hash(raw), now, jwtProperties.refreshTokenTtl()));
        return new AuthTokens(tokens.accessToken(user, now), raw, tokens.accessTokenTtlSeconds());
    }

    /** Revokes the session (the token's whole family). Idempotent: unknown or revoked tokens are ignored. */
    @Transactional
    public void logout(String refreshToken) {
        refreshTokens.findByHashForUpdate(TokenService.hash(refreshToken))
                .ifPresent(token -> refreshTokens.revokeFamily(token.getFamilyId(), clock.instant()));
    }

    private AuthTokens issue(User user, Instant now) {
        String raw = tokens.newRefreshToken();
        refreshTokens.save(RefreshToken.startFamily(user.getId(), TokenService.hash(raw), now,
                jwtProperties.refreshTokenTtl()));
        return new AuthTokens(tokens.accessToken(user, now), raw, tokens.accessTokenTtlSeconds());
    }

    private static ConflictException emailTaken() {
        return new ConflictException("email-taken", "An account with this email already exists");
    }

    private static UnauthorizedException invalidRefreshToken() {
        return new UnauthorizedException("invalid-refresh-token", "The refresh token is invalid, expired or revoked");
    }
}
