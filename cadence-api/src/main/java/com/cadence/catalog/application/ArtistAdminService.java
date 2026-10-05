package com.cadence.catalog.application;

import com.cadence.catalog.application.CatalogViews.ArtistView;
import com.cadence.catalog.domain.Artist;
import com.cadence.catalog.infrastructure.AlbumRepository;
import com.cadence.catalog.infrastructure.ArtistRepository;
import com.cadence.catalog.infrastructure.TrackRepository;
import com.cadence.common.error.ConflictException;
import com.cadence.common.error.NotFoundException;
import com.cadence.events.EntityChangedPayload.Action;
import com.cadence.events.ItemTypes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class ArtistAdminService {

    private final ArtistRepository artists;
    private final AlbumRepository albums;
    private final TrackRepository tracks;
    private final CatalogMapper mapper;
    private final CatalogEvents events;
    private final TrackSummaries summaries;
    private final Clock clock;

    ArtistAdminService(ArtistRepository artists, AlbumRepository albums, TrackRepository tracks, CatalogMapper mapper,
                       CatalogEvents events, TrackSummaries summaries, Clock clock) {
        this.summaries = summaries;
        this.artists = artists;
        this.albums = albums;
        this.tracks = tracks;
        this.mapper = mapper;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public ArtistView create(String name, String bio, String imageUrl, Boolean verified) {
        Artist artist = artists.save(new Artist(name, bio, imageUrl, Boolean.TRUE.equals(verified), clock.instant()));
        ArtistView view = mapper.toView(artist);
        events.entityChanged(ItemTypes.ARTIST, artist.getId(), Action.CREATED, view);
        return view;
    }

    @Transactional
    public ArtistView update(UUID id, String name, String bio, String imageUrl, Boolean verified) {
        Artist artist = load(id);
        artist.update(name, bio, imageUrl, verified, clock.instant());
        artists.flush();
        ArtistView view = mapper.toView(artist);
        events.entityChanged(ItemTypes.ARTIST, id, Action.UPDATED, view);
        events.tracksChanged(summaries.snapshots(tracks.findByCreditedArtist(id)));
        return view;
    }

    /** Only artists without albums or track credits can be deleted. */
    @Transactional
    public void delete(UUID id) {
        Artist artist = load(id);
        if (albums.existsByArtistId(id) || tracks.existsByCreditedArtist(id)) {
            throw new ConflictException("artist-in-use", "Delete or reassign the artist's albums and track credits first");
        }
        artists.delete(artist);
        events.entityChanged(ItemTypes.ARTIST, id, Action.DELETED, null);
    }

    private Artist load(UUID id) {
        return artists.findById(id).orElseThrow(() -> new NotFoundException("Artist", id));
    }
}
