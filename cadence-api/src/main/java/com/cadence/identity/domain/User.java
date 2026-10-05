package com.cadence.identity.domain;

import com.cadence.events.UuidV7;
import com.cadence.identity.Plan;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;
    private String email;
    private String passwordHash;
    private String displayName;
    private String avatarUrl;
    private String country;
    private LocalDate birthDate;
    @Enumerated(EnumType.STRING)
    private Plan plan;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = EnumSet.noneOf(Role.class);
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private long version;

    protected User() {
    }

    private User(String email, String passwordHash, String displayName, Set<Role> roles, Instant now) {
        this.id = UuidV7.generate();
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.displayName = displayName.strip();
        this.plan = Plan.FREE;
        this.roles = EnumSet.copyOf(roles);
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Public registration always yields a LISTENER (spec 4). */
    public static User registerListener(String email, String passwordHash, String displayName, Instant now) {
        return new User(email, passwordHash, displayName, EnumSet.of(Role.LISTENER), now);
    }

    public static User bootstrapAdmin(String email, String passwordHash, Instant now) {
        return new User(email, passwordHash, "Administrator", EnumSet.of(Role.ADMIN, Role.LISTENER), now);
    }

    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public void updateProfile(String displayName, String avatarUrl, String country, Instant now) {
        if (displayName != null) {
            this.displayName = displayName.strip();
        }
        if (avatarUrl != null) {
            this.avatarUrl = avatarUrl.isBlank() ? null : avatarUrl.strip();
        }
        if (country != null) {
            this.country = country;
        }
        this.updatedAt = now;
    }

    public void changePlan(Plan plan, Instant now) {
        this.plan = plan;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public String getCountry() {
        return country;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public Plan getPlan() {
        return plan;
    }

    public Set<Role> getRoles() {
        return Set.copyOf(roles);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
