package com.cadence.catalog.infrastructure;

import com.cadence.catalog.domain.Genre;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GenreRepository extends JpaRepository<Genre, UUID> {

    Optional<Genre> findByNameIgnoreCase(String name);

    List<Genre> findAllByOrderByNameAsc(Limit limit);

    List<Genre> findByNameGreaterThanOrderByNameAsc(String name, Limit limit);
}
