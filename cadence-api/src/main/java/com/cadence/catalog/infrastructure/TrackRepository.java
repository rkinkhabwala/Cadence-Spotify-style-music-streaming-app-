package com.cadence.catalog.infrastructure;

import com.cadence.catalog.TrackStatus;
import com.cadence.catalog.domain.Track;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TrackRepository extends JpaRepository<Track, UUID> {

    boolean existsByAlbumId(UUID albumId);

    @Query("select count(t) > 0 from Track t join t.artists a where a.artistId = :artistId")
    boolean existsByCreditedArtist(@Param("artistId") UUID artistId);

    List<Track> findByAlbumIdAndStatusOrderByDiscNumberAscTrackNumberAsc(UUID albumId, TrackStatus status);

    @Query("""
            select t from Track t join t.artists a
            where a.artistId = :artistId and t.status = com.cadence.catalog.TrackStatus.READY
            order by t.playCount desc, t.title asc, t.id asc
            """)
    List<Track> findTopReadyByArtist(@Param("artistId") UUID artistId, Limit limit);

    @Query("""
            select t from Track t
            where (:status is null or t.status = :status)
            order by t.updatedAt desc, t.id desc
            """)
    List<Track> findForAdmin(@Param("status") TrackStatus status, Limit limit);

    @Query("""
            select t from Track t
            where (:status is null or t.status = :status)
              and (t.updatedAt < :updatedAt or (t.updatedAt = :updatedAt and t.id < :id))
            order by t.updatedAt desc, t.id desc
            """)
    List<Track> findForAdminAfter(@Param("status") TrackStatus status, @Param("updatedAt") Instant updatedAt,
                                  @Param("id") UUID id, Limit limit);
}
