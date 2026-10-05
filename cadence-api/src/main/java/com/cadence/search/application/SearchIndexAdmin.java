package com.cadence.search.application;

import com.cadence.catalog.CatalogReplay;
import com.cadence.library.PlaylistReplay;
import com.cadence.search.infrastructure.SearchStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Keeps the indices present. Whenever an index has to be created (first start, wiped Elasticsearch, admin reindex),
 * the catalog and the public playlists are replayed through the outbox, so the indexer fills the new indices through
 * the same path as live changes.
 */
@Service
public class SearchIndexAdmin {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexAdmin.class);

    public record ReindexResult(int replayedEvents) {
    }

    private final SearchStore store;
    private final CatalogReplay catalogReplay;
    private final PlaylistReplay playlistReplay;
    private volatile boolean ready;

    SearchIndexAdmin(SearchStore store, CatalogReplay catalogReplay, PlaylistReplay playlistReplay) {
        this.store = store;
        this.catalogReplay = catalogReplay;
        this.playlistReplay = playlistReplay;
    }

    @EventListener(ApplicationReadyEvent.class)
    void atStartup() {
        try {
            ensureIndices();
        } catch (RuntimeException e) {
            log.warn("Search indices not ready yet (retried on first use): {}", e.toString());
        }
    }

    /** Cheap after the first success. */
    public void ensureIndices() {
        if (ready) {
            return;
        }
        synchronized (this) {
            if (!ready) {
                if (store.ensureIndices()) {
                    int replayed = replay();
                    log.info("Created search indices; replayed {} events to fill them", replayed);
                }
                ready = true;
            }
        }
    }

    /** Drops and rebuilds every index from a full replay. Search results are incomplete until the replay is consumed. */
    public synchronized ReindexResult reindex() {
        ready = false;
        store.dropIndices();
        store.ensureIndices();
        int replayed = replay();
        ready = true;
        log.info("Search reindex requested: replayed {} events", replayed);
        return new ReindexResult(replayed);
    }

    private int replay() {
        return catalogReplay.replayEntityChanged() + playlistReplay.replayPublicPlaylists();
    }
}
