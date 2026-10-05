package com.cadence.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Public, read-only catalog API for other bounded contexts. */
public interface CatalogQueries {

    /** Track in any status, or empty if it doesn't exist. */
    Optional<TrackSummary> findTrack(UUID trackId);

    /** Tracks in any status keyed by id; unknown ids are absent from the map. */
    Map<UUID, TrackSummary> findTracks(Collection<UUID> trackIds);

    /** Album references keyed by id; unknown ids are absent. */
    Map<UUID, CatalogRefs.AlbumRef> findAlbums(Collection<UUID> albumIds);

    /** Artist references keyed by id; unknown ids are absent. */
    Map<UUID, CatalogRefs.ArtistRef> findArtists(Collection<UUID> artistIds);

    /** Released albums (release date today or earlier) with at least one READY track, newest first. */
    List<AlbumSummary> newReleases(int limit);

    /** The genres of each track's album; tracks without genres (or unknown) are absent. */
    Map<UUID, List<String>> genresOf(Collection<UUID> trackIds);

    /** Ids of READY tracks by all-time play count, most played first. */
    List<UUID> popularTrackIds(int limit);

    /** Ids of READY tracks whose album has one of the genres (case-insensitive), by all-time play count. */
    List<UUID> popularTrackIdsInGenres(Collection<String> genres, int limit);

    boolean artistExists(UUID artistId);

    boolean albumExists(UUID albumId);
}
