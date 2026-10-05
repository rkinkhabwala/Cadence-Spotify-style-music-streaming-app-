package com.cadence.catalog.infrastructure;

import com.cadence.catalog.TrackStatus;
import com.cadence.catalog.domain.Track;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TrackRepository extends JpaRepository<Track, UUID> {

    boolean existsByAlbumId(UUID albumId);

    List<Track> findByAlbumId(UUID albumId);

    @Query("select distinct t from Track t join t.artists a where a.artistId = :artistId")
    List<Track> findByCreditedArtist(@Param("artistId") UUID artistId);

    interface TrackGenreRow {
        UUID getTrackId();

        String getGenre();
    }

    /** The genres of each track's album. */
    @Query(nativeQuery = true, value = """
            SELECT t.id AS trackId, g.name AS genre
            FROM tracks t JOIN album_genres ag ON ag.album_id = t.album_id JOIN genres g ON g.id = ag.genre_id
            WHERE t.id IN (:ids)
            ORDER BY g.name
            """)
    List<TrackGenreRow> findGenres(@Param("ids") java.util.Collection<UUID> trackIds);

    @Query("""
            select t.id from Track t where t.status = com.cadence.catalog.TrackStatus.READY
            order by t.playCount desc, t.createdAt desc, t.id
            """)
    List<UUID> findMostPlayedReadyIds(Limit limit);

    @Query("""
            select t.id from Track t
            where t.status = com.cadence.catalog.TrackStatus.READY and t.albumId in (
                select a.id from Album a join a.genres g where lower(g.name) in :genres)
            order by t.playCount desc, t.createdAt desc, t.id
            """)
    List<UUID> findMostPlayedReadyIdsInGenres(@Param("genres") java.util.Collection<String> lowerCaseGenres, Limit limit);

    /** Bulk update: no version bump and no updatedAt change, a play is not a catalog edit. @return rows updated */
    @Modifying
    @Query("update Track t set t.playCount = t.playCount + 1 where t.id = :id")
    int incrementPlayCount(@Param("id") UUID id);

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
