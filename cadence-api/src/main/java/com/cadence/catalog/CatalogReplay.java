package com.cadence.catalog;

/** Republishes catalog state as events, e.g. when the search index is rebuilt. */
public interface CatalogReplay {

    /**
     * Writes a {@code catalog.entity-changed} UPDATED event with the current snapshot of every artist, album and
     * track to the outbox (one transaction).
     *
     * @return the number of events written
     */
    int replayEntityChanged();
}
