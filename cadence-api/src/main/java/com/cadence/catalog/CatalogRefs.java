package com.cadence.catalog;

import java.util.UUID;

/** Small reference records embedded in catalog read models. */
public final class CatalogRefs {

    private CatalogRefs() {
    }

    public record ArtistRef(UUID id, String name) {
    }

    public record ArtistCredit(UUID id, String name, String role) {
    }

    public record AlbumRef(UUID id, String title, String coverUrl) {
    }
}
