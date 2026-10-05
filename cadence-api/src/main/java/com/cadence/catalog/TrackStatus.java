package com.cadence.catalog;

/** Track lifecycle (spec 4). Only READY tracks are playable. */
public enum TrackStatus {
    DRAFT,
    PROCESSING,
    READY,
    FAILED
}
