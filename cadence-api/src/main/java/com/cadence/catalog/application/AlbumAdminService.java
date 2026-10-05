package com.cadence.catalog.application;

import com.cadence.catalog.CatalogRefs.ArtistRef;
import com.cadence.catalog.application.CatalogViews.AlbumView;
import com.cadence.catalog.domain.Album;
import com.cadence.catalog.domain.AlbumType;
import com.cadence.catalog.domain.Artist;
import com.cadence.catalog.domain.Genre;
import com.cadence.catalog.infrastructure.AlbumRepository;
import com.cadence.catalog.infrastructure.ArtistRepository;
import com.cadence.catalog.infrastructure.GenreRepository;
import com.cadence.catalog.infrastructure.TrackRepository;
import com.cadence.common.error.BadRequestException;
import com.cadence.common.error.ConflictException;
import com.cadence.common.error.NotFoundException;
import com.cadence.events.EntityChangedPayload.Action;
import com.cadence.events.ItemTypes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class AlbumAdminService {

    private final AlbumRepository albums;
    private final ArtistRepository artists;
    private final GenreRepository genres;
    private final TrackRepository tracks;
    private final CatalogMapper mapper;
    private final CatalogEvents events;
    private final TrackSummaries summaries;
    private final Clock clock;

    AlbumAdminService(AlbumRepository albums, ArtistRepository artists, GenreRepository genres, TrackRepository tracks,
                      CatalogMapper mapper, CatalogEvents events, TrackSummaries summaries, Clock clock) {
        this.summaries = summaries;
        this.albums = albums;
        this.artists = artists;
        this.genres = genres;
        this.tracks = tracks;
        this.mapper = mapper;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public AlbumView create(String title, UUID artistId, LocalDate releaseDate, AlbumType type, String coverUrl,
                            String label, List<String> genreNames) {
        Artist artist = artists.findById(artistId)
                .orElseThrow(() -> new BadRequestException("unknown-artist", "Artist " + artistId + " does not exist"));
        Album album = albums.save(new Album(title, artistId, releaseDate, type, coverUrl, label,
                resolveGenres(genreNames == null ? List.of() : genreNames), clock.instant()));
        AlbumView view = mapper.toView(album, new ArtistRef(artist.getId(), artist.getName()));
        events.entityChanged(ItemTypes.ALBUM, album.getId(), Action.CREATED, view);
        return view;
    }

    @Transactional
    public AlbumView update(UUID id, String title, LocalDate releaseDate, AlbumType type, String coverUrl, String label,
                            List<String> genreNames) {
        Album album = load(id);
        album.update(title, releaseDate, type, coverUrl, label, genreNames == null ? null : resolveGenres(genreNames),
                clock.instant());
        albums.flush();
        Artist artist = artists.getReferenceById(album.getArtistId());
        AlbumView view = mapper.toView(album, new ArtistRef(artist.getId(), artist.getName()));
        events.entityChanged(ItemTypes.ALBUM, id, Action.UPDATED, view);
        events.tracksChanged(summaries.snapshots(tracks.findByAlbumId(id)));
        return view;
    }

    /** Only albums without tracks can be deleted. */
    @Transactional
    public void delete(UUID id) {
        Album album = load(id);
        if (tracks.existsByAlbumId(id)) {
            throw new ConflictException("album-has-tracks", "Delete the album's tracks first");
        }
        albums.delete(album);
        events.entityChanged(ItemTypes.ALBUM, id, Action.DELETED, null);
    }

    /** Genres are created on first use, matched case-insensitively. */
    private Set<Genre> resolveGenres(List<String> names) {
        Set<Genre> resolved = new LinkedHashSet<>();
        for (String name : names) {
            String trimmed = name.strip();
            resolved.add(genres.findByNameIgnoreCase(trimmed).orElseGet(() -> genres.saveAndFlush(new Genre(trimmed))));
        }
        return resolved;
    }

    private Album load(UUID id) {
        return albums.findById(id).orElseThrow(() -> new NotFoundException("Album", id));
    }
}
