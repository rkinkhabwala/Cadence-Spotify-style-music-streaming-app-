package com.cadence.library.infrastructure;

import com.cadence.library.domain.LikedTrack;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LikedTrackRepository extends JpaRepository<LikedTrack, LikedTrack.Key> {

    /** @return 1 if newly liked, 0 if it already was (race-free idempotency) */
    @Modifying
    @Query(value = "INSERT INTO liked_tracks (user_id, track_id, liked_at) VALUES (:userId, :trackId, :at) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int like(@Param("userId") UUID userId, @Param("trackId") UUID trackId, @Param("at") Instant at);

    @Modifying
    @Query(value = "DELETE FROM liked_tracks WHERE user_id = :userId AND track_id = :trackId", nativeQuery = true)
    int unlike(@Param("userId") UUID userId, @Param("trackId") UUID trackId);

    @Query("select l from LikedTrack l where l.id.userId = :userId order by l.likedAt desc, l.id.trackId desc")
    List<LikedTrack> firstPage(@Param("userId") UUID userId, Limit limit);

    @Query("""
            select l from LikedTrack l where l.id.userId = :userId
              and (l.likedAt < :at or (l.likedAt = :at and l.id.trackId < :trackId))
            order by l.likedAt desc, l.id.trackId desc""")
    List<LikedTrack> pageAfter(@Param("userId") UUID userId, @Param("at") Instant at, @Param("trackId") UUID trackId,
                               Limit limit);

    @Query("select l.id.trackId from LikedTrack l where l.id.userId = :userId and l.id.trackId in :trackIds")
    List<UUID> findLikedAmong(@Param("userId") UUID userId, @Param("trackIds") java.util.Collection<UUID> trackIds);
}
