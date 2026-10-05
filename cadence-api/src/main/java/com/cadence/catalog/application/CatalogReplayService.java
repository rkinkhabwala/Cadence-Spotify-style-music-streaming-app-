package com.cadence.catalog.application;

import com.cadence.catalog.CatalogRefs.ArtistRef;
import com.cadence.catalog.CatalogReplay;
import com.cadence.catalog.TrackSummary;
import com.cadence.catalog.domain.Album;
import com.cadence.catalog.domain.Artist;
import com.cadence.catalog.infrastructure.AlbumRepository;
import com.cadence.catalog.infrastructure.ArtistRepository;
import com.cadence.catalog.infrastructure.TrackRepository;
import com.cadence.events.EntityChangedPayload.Action;
import com.cadence.events.ItemTypes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Loads the whole catalog at once: fine for this local catalog size (hundreds of entities). */
@Service
class CatalogReplayService implements CatalogReplay {

    private final ArtistRepository artists;
    private final AlbumRepository albums;
    private final TrackRepository tracks;
    private final TrackSummaries summaries;
    private final CatalogMapper mapper;
    private final CatalogEvents events;

    CatalogReplayService(ArtistRepository artists, AlbumRepository albums, TrackRepository tracks,
                         TrackSummaries summaries, CatalogMapper mapper, CatalogEvents events) {
        this.artists = artists;
        this.albums = albums;
        this.tracks = tracks;
        this.summaries = summaries;
        this.mapper = mapper;
        this.events = events;
    }

    @Override
    @Transactional
    public int replayEntityChanged() {
        List<Artist> allArtists = artists.findAll();
        Map<java.util.UUID, Artist> artistById = allArtists.stream()
                .collect(Collectors.toMap(Artist::getId, Function.identity()));
        allArtists.forEach(a -> events.entityChanged(ItemTypes.ARTIST, a.getId(), Action.UPDATED, mapper.toView(a)));
        List<Album> allAlbums = albums.findAll();
        for (Album album : allAlbums) {
            Artist artist = artistById.get(album.getArtistId());
            events.entityChanged(ItemTypes.ALBUM, album.getId(), Action.UPDATED,
                    mapper.toView(album, new ArtistRef(artist.getId(), artist.getName())));
        }
        List<TrackSummary> allTracks = summaries.of(tracks.findAll());
        allTracks.forEach(t -> events.entityChanged(ItemTypes.SONG, t.id(), Action.UPDATED, t));
        return allArtists.size() + allAlbums.size() + allTracks.size();
    }
}
