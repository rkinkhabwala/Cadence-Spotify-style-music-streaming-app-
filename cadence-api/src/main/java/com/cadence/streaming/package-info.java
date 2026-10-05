/**
 * Streaming: playback authorization, HLS manifest serving with presigned segment URLs, and the HTTP Range
 * fallback. Reads tracks via {@link com.cadence.catalog.CatalogQueries} and plans via {@link com.cadence.identity.UserAccounts}.
 */
@ApplicationModule(displayName = "Streaming")
package com.cadence.streaming;

import org.springframework.modulith.ApplicationModule;
