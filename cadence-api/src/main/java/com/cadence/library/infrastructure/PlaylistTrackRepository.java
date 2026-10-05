package com.cadence.library.infrastructure;

import com.cadence.library.domain.PlaylistTrack;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code position} is {@code COLLATE "C"}, so SQL ordering matches Java string ordering of fractional keys. */
public interface PlaylistTrackRepository extends JpaRepository<PlaylistTrack, PlaylistTrack.Key> {

    List<PlaylistTrack> findByIdPlaylistIdOrderByPositionAsc(UUID playlistId, Limit limit);

    @Query("select t from PlaylistTrack t where t.id.playlistId = :playlistId and t.position > :after order by t.position")
    List<PlaylistTrack> findPageAfter(@Param("playlistId") UUID playlistId, @Param("after") String after, Limit limit);

    long countByIdPlaylistId(UUID playlistId);

    List<PlaylistTrack> findByIdPlaylistIdAndIdTrackIdIn(UUID playlistId, Collection<UUID> trackIds);

    Optional<PlaylistTrack> findByIdPlaylistIdAndIdTrackId(UUID playlistId, UUID trackId);

    /** Key at 0-based {@code index} in playlist order, if any. */
    @Query(value = "SELECT position FROM playlist_tracks WHERE playlist_id = :playlistId ORDER BY position OFFSET :index LIMIT 1",
            nativeQuery = true)
    Optional<String> positionAt(@Param("playlistId") UUID playlistId, @Param("index") long index);

    @Query("""
            select t.position from PlaylistTrack t
            where t.id.playlistId = :playlistId and t.position > :after and t.id.trackId <> :excluded
            order by t.position""")
    List<String> positionsAfter(@Param("playlistId") UUID playlistId, @Param("after") String after,
                                @Param("excluded") UUID excluded, Limit limit);

    @Query("""
            select t.position from PlaylistTrack t
            where t.id.playlistId = :playlistId and t.id.trackId <> :excluded
            order by t.position""")
    List<String> firstPositions(@Param("playlistId") UUID playlistId, @Param("excluded") UUID excluded, Limit limit);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PlaylistTrack t where t.id.playlistId = :playlistId and t.id.trackId in :trackIds")
    int deleteTracks(@Param("playlistId") UUID playlistId, @Param("trackIds") Collection<UUID> trackIds);
}
