package com.cadence.catalog.infrastructure;

import com.cadence.catalog.domain.Album;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AlbumRepository extends JpaRepository<Album, UUID> {

    boolean existsByArtistId(UUID artistId);

    List<Album> findByArtistIdOrderByReleaseDateDescIdDesc(UUID artistId, Limit limit);

    @Query("""
            select a from Album a
            where a.artistId = :artistId
              and (a.releaseDate < :releaseDate or (a.releaseDate = :releaseDate and a.id < :id))
            order by a.releaseDate desc, a.id desc
            """)
    List<Album> findByArtistAfter(@Param("artistId") UUID artistId, @Param("releaseDate") LocalDate releaseDate,
                                  @Param("id") UUID id, Limit limit);
}
