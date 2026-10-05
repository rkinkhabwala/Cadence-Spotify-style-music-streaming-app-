package com.cadence.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshTokenTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final Duration TTL = Duration.ofDays(30);

    @Test
    void rotationKeepsTheFamilyAndRetiresThePredecessor() {
        RefreshToken first = RefreshToken.startFamily(UUID.randomUUID(), "h1", NOW, TTL);

        RefreshToken second = first.rotate("h2", NOW.plusSeconds(60), TTL);

        assertThat(second.getFamilyId()).isEqualTo(first.getFamilyId());
        assertThat(second.getUserId()).isEqualTo(first.getUserId());
        assertThat(second.isUsable(NOW.plusSeconds(61))).isTrue();
        assertThat(first.isUsable(NOW.plusSeconds(61))).isFalse();
        assertThat(first.isReuse()).isTrue();
        assertThat(first.getReplacedBy()).isEqualTo(second.getId());
        assertThat(second.getExpiresAt()).isEqualTo(NOW.plusSeconds(60).plus(TTL));
    }

    @Test
    void expiredOrRevokedTokensAreNotUsableButNotReuse() {
        RefreshToken expired = RefreshToken.startFamily(UUID.randomUUID(), "h", NOW, TTL);
        RefreshToken loggedOut = RefreshToken.startFamily(UUID.randomUUID(), "h", NOW, TTL);
        loggedOut.revoke(NOW);

        assertThat(expired.isUsable(NOW.plus(TTL))).isFalse();
        assertThat(loggedOut.isUsable(NOW)).isFalse();
        assertThat(loggedOut.isReuse()).isFalse();
        assertThatThrownBy(() -> loggedOut.rotate("h2", NOW, TTL)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void newLoginsStartNewFamilies() {
        UUID user = UUID.randomUUID();

        assertThat(RefreshToken.startFamily(user, "a", NOW, TTL).getFamilyId())
                .isNotEqualTo(RefreshToken.startFamily(user, "b", NOW, TTL).getFamilyId());
    }
}
