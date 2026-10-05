/**
 * Activity: play reporting, listening history, top tracks, track popularity and the home shelves. Reads the catalog
 * through {@link com.cadence.catalog.CatalogQueries} and publishes {@code activity.track-played} through the outbox.
 */
@ApplicationModule(displayName = "Activity")
package com.cadence.activity;

import org.springframework.modulith.ApplicationModule;
