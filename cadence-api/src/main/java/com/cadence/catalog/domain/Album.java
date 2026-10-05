package com.cadence.catalog.domain;

import com.cadence.events.UuidV7;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "albums")
public class Album {

    @Id
    private UUID id;
    private String title;
    private UUID artistId;
    private LocalDate releaseDate;
    @Enumerated(EnumType.STRING)
    private AlbumType type;
    private String coverUrl;
    private String label;
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "album_genres", joinColumns = @JoinColumn(name = "album_id"),
            inverseJoinColumns = @JoinColumn(name = "genre_id"))
    private Set<Genre> genres = new HashSet<>();
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private long version;

    protected Album() {
    }

    public Album(String title, UUID artistId, LocalDate releaseDate, AlbumType type, String coverUrl, String label,
                 Set<Genre> genres, Instant now) {
        this.id = UuidV7.generate();
        this.title = title.strip();
        this.artistId = artistId;
        this.releaseDate = releaseDate;
        this.type = type;
        this.coverUrl = Texts.blankToNull(coverUrl);
        this.label = Texts.blankToNull(label);
        this.genres = new HashSet<>(genres);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String title, LocalDate releaseDate, AlbumType type, String coverUrl, String label,
                       Set<Genre> genres, Instant now) {
        if (title != null) {
            this.title = title.strip();
        }
        if (releaseDate != null) {
            this.releaseDate = releaseDate;
        }
        if (type != null) {
            this.type = type;
        }
        if (coverUrl != null) {
            this.coverUrl = Texts.blankToNull(coverUrl);
        }
        if (label != null) {
            this.label = Texts.blankToNull(label);
        }
        if (genres != null) {
            this.genres = new HashSet<>(genres);
        }
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public UUID getArtistId() {
        return artistId;
    }

    public LocalDate getReleaseDate() {
        return releaseDate;
    }

    public AlbumType getType() {
        return type;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public String getLabel() {
        return label;
    }

    public List<String> getGenreNames() {
        return genres.stream().map(Genre::getName).sorted(Comparator.naturalOrder()).toList();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
