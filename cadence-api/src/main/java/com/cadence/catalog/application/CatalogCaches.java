package com.cadence.catalog.application;

/** Redis caches of the public artist and album pages (spec 7: TTL 10 min, evicted on change). */
public final class CatalogCaches {

    public static final String ARTISTS = "catalog.artist";
    public static final String ALBUMS = "catalog.album";

    private CatalogCaches() {
    }
}
