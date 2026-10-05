/**
 * Catalog: artists, albums, tracks, genres and admin uploads. Other contexts read it through
 * {@link com.cadence.catalog.CatalogQueries} and react to {@code catalog.*} events.
 */
@ApplicationModule(displayName = "Catalog")
package com.cadence.catalog;

import org.springframework.modulith.ApplicationModule;
