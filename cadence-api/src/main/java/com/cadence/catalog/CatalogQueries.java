package com.cadence.catalog;

import java.util.Collection;
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

    boolean artistExists(UUID artistId);

    boolean albumExists(UUID albumId);
}
