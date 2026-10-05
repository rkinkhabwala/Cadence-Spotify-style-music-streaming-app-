/**
 * Search: Elasticsearch indices of artists, albums, READY tracks and PUBLIC playlists, kept up to date from
 * {@code catalog.entity-changed} and {@code library.playlist-changed} events, plus the search and suggest APIs.
 */
@ApplicationModule(displayName = "Search")
package com.cadence.search;

import org.springframework.modulith.ApplicationModule;
