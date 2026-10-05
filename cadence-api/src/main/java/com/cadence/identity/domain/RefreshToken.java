package com.cadence.identity.domain;

import com.cadence.events.UuidV7;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A hashed, single-use refresh token. Tokens issued from one login share a {@code familyId}; presenting a token
 * that was already rotated means it leaked, so the whole family is revoked (spec 6).
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    private UUID id;
    private UUID userId;
    private UUID familyId;
    private String tokenHash;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant revokedAt;
    private UUID replacedBy;

    protected RefreshToken() {
    }

    private RefreshToken(UUID userId, UUID familyId, String tokenHash, Instant now, Duration ttl) {
        this.id = UuidV7.generate();
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.createdAt = now;
        this.expiresAt = now.plus(ttl);
    }

    /** First token of a new login session. */
    public static RefreshToken startFamily(UUID userId, String tokenHash, Instant now, Duration ttl) {
        return new RefreshToken(userId, UuidV7.generate(), tokenHash, now, ttl);
    }

    /** Issues the successor of this token and retires this one. */
    public RefreshToken rotate(String successorHash, Instant now, Duration ttl) {
        if (!isUsable(now)) {
            throw new IllegalStateException("Cannot rotate an unusable refresh token");
        }
        RefreshToken successor = new RefreshToken(userId, familyId, successorHash, now, ttl);
        this.revokedAt = now;
        this.replacedBy = successor.id;
        return successor;
    }

    /** Presenting a token that already has a successor is the signature of token theft. */
    public boolean isReuse() {
        return replacedBy != null;
    }

    public boolean isUsable(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getReplacedBy() {
        return replacedBy;
    }
}
