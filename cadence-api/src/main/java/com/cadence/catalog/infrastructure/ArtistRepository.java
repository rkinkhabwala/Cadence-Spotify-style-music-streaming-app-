package com.cadence.catalog.infrastructure;

import com.cadence.catalog.domain.Artist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ArtistRepository extends JpaRepository<Artist, UUID> {
}
