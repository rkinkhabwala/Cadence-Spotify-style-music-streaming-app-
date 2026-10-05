package com.cadence.catalog.domain;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.util.UUID;

/** Credit of an artist on a track (spec 4 TrackArtist). */
@Embeddable
public record TrackArtist(UUID artistId, @Enumerated(EnumType.STRING) ArtistRole role) {
}
