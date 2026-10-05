package com.cadence.events;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Payload of {@code catalog.entity-changed}: what happened and, unless deleted, a snapshot of the entity
 * as exposed by the catalog's public read model (so consumers such as search need no call-back).
 */
public record EntityChangedPayload(Action action, JsonNode snapshot) {

    public enum Action {
        CREATED,
        UPDATED,
        DELETED
    }
}
