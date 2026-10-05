package com.cadence.library;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/** Public, read-only library API for other bounded contexts. */
public interface LibraryQueries {

    /** The subset of {@code trackIds} the user has liked. */
    Set<UUID> likedAmong(UUID userId, Collection<UUID> trackIds);
}
