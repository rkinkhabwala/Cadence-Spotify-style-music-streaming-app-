package com.cadence.catalog.domain;

import com.cadence.events.UuidV7;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "artists")
public class Artist {

    @Id
    private UUID id;
    private String name;
    private String bio;
    private String imageUrl;
    private boolean verified;
    private long monthlyListeners;
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private long version;

    protected Artist() {
    }

    public Artist(String name, String bio, String imageUrl, boolean verified, Instant now) {
        this.id = UuidV7.generate();
        this.name = name.strip();
        this.bio = Texts.blankToNull(bio);
        this.imageUrl = Texts.blankToNull(imageUrl);
        this.verified = verified;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** {@code null} = unchanged, blank = cleared (optional fields). */
    public void update(String name, String bio, String imageUrl, Boolean verified, Instant now) {
        if (name != null) {
            this.name = name.strip();
        }
        if (bio != null) {
            this.bio = Texts.blankToNull(bio);
        }
        if (imageUrl != null) {
            this.imageUrl = Texts.blankToNull(imageUrl);
        }
        if (verified != null) {
            this.verified = verified;
        }
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getBio() {
        return bio;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public boolean isVerified() {
        return verified;
    }

    public long getMonthlyListeners() {
        return monthlyListeners;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
