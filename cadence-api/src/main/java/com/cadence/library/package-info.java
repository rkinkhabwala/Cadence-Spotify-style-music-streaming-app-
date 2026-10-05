/**
 * Library: playlists, liked tracks, followed artists and saved albums. Reads the catalog through
 * {@link com.cadence.catalog.CatalogQueries}; publishes {@code library.*} events through the outbox.
 */
@ApplicationModule(displayName = "Library")
package com.cadence.library;

import org.springframework.modulith.ApplicationModule;
