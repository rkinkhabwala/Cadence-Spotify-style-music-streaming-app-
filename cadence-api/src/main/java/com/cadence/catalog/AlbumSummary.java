package com.cadence.catalog;

import com.cadence.catalog.CatalogRefs.ArtistRef;

import java.time.LocalDate;
import java.util.UUID;

/** Album as shown on cards (home shelves). {@code type} is ALBUM, SINGLE or EP. */
public record AlbumSummary(UUID id, String title, String type, LocalDate releaseDate, String coverUrl, ArtistRef artist) {
}
