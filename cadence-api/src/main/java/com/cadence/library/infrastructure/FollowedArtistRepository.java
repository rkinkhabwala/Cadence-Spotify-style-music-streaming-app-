package com.cadence.library.infrastructure;

import com.cadence.library.domain.FollowedArtist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/** Follow relation (spec 4 FollowedArtist); writes are race-free and idempotent. */
public interface FollowedArtistRepository extends JpaRepository<FollowedArtist, FollowedArtist.Key> {

    @Modifying
    @Query(value = "INSERT INTO followed_artists (user_id, artist_id, followed_at) VALUES (:userId, :artistId, :at) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int follow(@Param("userId") UUID userId, @Param("artistId") UUID artistId, @Param("at") Instant at);

    @Modifying
    @Query(value = "DELETE FROM followed_artists WHERE user_id = :userId AND artist_id = :artistId", nativeQuery = true)
    int unfollow(@Param("userId") UUID userId, @Param("artistId") UUID artistId);
}
