package com.cadence.catalog.application;

import com.cadence.catalog.CatalogRefs.AlbumRef;
import com.cadence.catalog.CatalogRefs.ArtistCredit;
import com.cadence.catalog.TrackSummary;
import com.cadence.catalog.domain.Album;
import com.cadence.catalog.domain.Artist;
import com.cadence.catalog.domain.Track;
import com.cadence.catalog.domain.TrackArtist;
import com.cadence.catalog.infrastructure.AlbumRepository;
import com.cadence.catalog.infrastructure.ArtistRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds {@link TrackSummary} read models with one query per referenced table (no N+1). */
@Component
class TrackSummaries {

    private final AlbumRepository albums;
    private final ArtistRepository artists;

    TrackSummaries(AlbumRepository albums, ArtistRepository artists) {
        this.albums = albums;
        this.artists = artists;
    }

    List<TrackSummary> of(Collection<Track> tracks) {
        Set<UUID> albumIds = tracks.stream().map(Track::getAlbumId).collect(Collectors.toSet());
        Set<UUID> artistIds = tracks.stream().flatMap(t -> t.getArtists().stream()).map(TrackArtist::artistId)
                .collect(Collectors.toSet());
        Map<UUID, Album> albumById = albums.findAllById(albumIds).stream()
                .collect(Collectors.toMap(Album::getId, Function.identity()));
        Map<UUID, Artist> artistById = artists.findAllById(artistIds).stream()
                .collect(Collectors.toMap(Artist::getId, Function.identity()));
        return tracks.stream().map(t -> toSummary(t, albumById.get(t.getAlbumId()), artistById)).toList();
    }

    TrackSummary of(Track track) {
        return of(List.of(track)).getFirst();
    }

    private static TrackSummary toSummary(Track track, Album album, Map<UUID, Artist> artistById) {
        List<ArtistCredit> credits = track.getArtists().stream()
                .sorted((a, b) -> a.role().compareTo(b.role()))
                .map(c -> new ArtistCredit(c.artistId(),
                        artistById.containsKey(c.artistId()) ? artistById.get(c.artistId()).getName() : null,
                        c.role().name()))
                .toList();
        AlbumRef albumRef = album == null ? null : new AlbumRef(album.getId(), album.getTitle(), album.getCoverUrl());
        return new TrackSummary(track.getId(), track.getTitle(), track.getDurationMs(), track.isExplicit(),
                track.getDiscNumber(), track.getTrackNumber(), track.getStatus(), track.getPlayCount(), albumRef, credits);
    }
}
