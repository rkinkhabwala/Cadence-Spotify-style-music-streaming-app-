package com.cadence.library.infrastructure;

import com.cadence.library.domain.FollowedArtist;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Follow relation (spec 4 FollowedArtist); writes are race-free and idempotent. */
public interface FollowedArtistRepository extends JpaRepository<FollowedArtist, FollowedArtist.Key> {

    @Modifying
    @Query(value = "INSERT INTO followed_artists (user_id, artist_id, followed_at) VALUES (:userId, :artistId, :at) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int follow(@Param("userId") UUID userId, @Param("artistId") UUID artistId, @Param("at") Instant at);

    @Query("select f from FollowedArtist f where f.id.userId = :userId order by f.followedAt desc, f.id.artistId desc")
    List<FollowedArtist> firstPage(@Param("userId") UUID userId, Limit limit);

    @Query("""
            select f from FollowedArtist f where f.id.userId = :userId
              and (f.followedAt < :at or (f.followedAt = :at and f.id.artistId < :artistId))
            order by f.followedAt desc, f.id.artistId desc""")
    List<FollowedArtist> pageAfter(@Param("userId") UUID userId, @Param("at") Instant at, @Param("artistId") UUID artistId,
                                   Limit limit);

    @Modifying
    @Query(value = "DELETE FROM followed_artists WHERE user_id = :userId AND artist_id = :artistId", nativeQuery = true)
    int unfollow(@Param("userId") UUID userId, @Param("artistId") UUID artistId);
}
