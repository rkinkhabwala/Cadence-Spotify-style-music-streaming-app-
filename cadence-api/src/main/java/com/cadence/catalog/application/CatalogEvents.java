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
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/** Writes catalog events to the outbox (always inside the caller's transaction). */
@Component
class CatalogEvents {

    private final OutboxWriter outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    CatalogEvents(OutboxWriter outbox, ObjectMapper objectMapper, Clock clock) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** {@code catalog.entity-changed}; {@code snapshot} is the public read model, {@code null} on delete. */
    void entityChanged(String itemType, UUID id, Action action, Object snapshot) {
        EntityChangedPayload payload = new EntityChangedPayload(action,
                snapshot == null ? null : objectMapper.valueToTree(snapshot));
        outbox.append(Topics.CATALOG_ENTITY_CHANGED, id.toString(), EventEnvelope.create(EventTypes.ENTITY_CHANGED,
                clock.instant(), null, itemType, id, payload, objectMapper));
    }

    /** {@code catalog.track-uploaded}; the event id is the transcode job id. */
    void trackUploaded(UUID trackId, UUID jobId, TrackUploadedPayload payload) {
        outbox.append(Topics.CATALOG_TRACK_UPLOADED, trackId.toString(), EventEnvelope.create(jobId,
                EventTypes.TRACK_UPLOADED, clock.instant(), null, ItemTypes.SONG, trackId, payload, objectMapper));
    }
}
