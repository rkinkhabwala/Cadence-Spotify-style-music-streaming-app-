package com.cadence.catalog.application;

import com.cadence.common.outbox.OutboxWriter;
import com.cadence.events.EntityChangedPayload;
import com.cadence.events.EntityChangedPayload.Action;
import com.cadence.events.EventEnvelope;
import com.cadence.events.EventTypes;
import com.cadence.events.ItemTypes;
import com.cadence.events.Topics;
import com.cadence.events.TrackUploadedPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Writes catalog events to the outbox (always inside the caller's transaction). Every catalog change passes through
 * {@link #entityChanged}, so it is also where the artist/album page caches are cleared, after the commit.
 */
@Component
class CatalogEvents {

    private static final Logger log = LoggerFactory.getLogger(CatalogEvents.class);

    private final OutboxWriter outbox;
    private final ObjectMapper objectMapper;
    private final CacheManager cacheManager;
    private final Clock clock;

    CatalogEvents(OutboxWriter outbox, ObjectMapper objectMapper, CacheManager cacheManager, Clock clock) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.cacheManager = cacheManager;
        this.clock = clock;
    }

    /** {@code catalog.entity-changed}; {@code snapshot} is the public read model, {@code null} on delete. */
    void entityChanged(String itemType, UUID id, Action action, Object snapshot) {
        EntityChangedPayload payload = new EntityChangedPayload(action,
                snapshot == null ? null : objectMapper.valueToTree(snapshot));
        outbox.append(Topics.CATALOG_ENTITY_CHANGED, id.toString(), EventEnvelope.create(EventTypes.ENTITY_CHANGED,
                clock.instant(), null, itemType, id, payload, objectMapper));
        clearPageCachesAfterCommit();
    }

    /**
     * Pages embed denormalized names (an album page shows artist names, an artist page shows album titles), so any
     * change clears both caches rather than guessing the affected keys. Catalog writes are rare admin actions.
     */
    private void clearPageCachesAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            clearPageCaches();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                clearPageCaches();
            }
        });
    }

    private void clearPageCaches() {
        for (String name : List.of(CatalogCaches.ARTISTS, CatalogCaches.ALBUMS)) {
            try {
                Cache cache = cacheManager.getCache(name);
                if (cache != null) {
                    cache.clear();
                }
            } catch (RuntimeException e) {
                log.warn("Could not clear cache {} (entries expire within the TTL): {}", name, e.toString());
            }
        }
    }

    /**
     * Re-publishes tracks after a change to their album or an artist they credit, so every track event carries
     * current names and genres (the recommender reads tracks only; D88).
     */
    void tracksChanged(List<TrackSnapshot> tracks) {
        tracks.forEach(t -> entityChanged(ItemTypes.SONG, t.id(), Action.UPDATED, t));
    }

    /** {@code catalog.track-uploaded}; the event id is the transcode job id. */
    void trackUploaded(UUID trackId, UUID jobId, TrackUploadedPayload payload) {
        outbox.append(Topics.CATALOG_TRACK_UPLOADED, trackId.toString(), EventEnvelope.create(jobId,
                EventTypes.TRACK_UPLOADED, clock.instant(), null, ItemTypes.SONG, trackId, payload, objectMapper));
    }
}
