package com.cadence.library.infrastructure;

import com.cadence.library.domain.PlaylistCollaborator;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PlaylistCollaboratorRepository extends JpaRepository<PlaylistCollaborator, PlaylistCollaborator.Key> {

    /** @return 1 if the user joined now, 0 if already a collaborator (race-free idempotency) */
    @Modifying
    @Query(value = """
            INSERT INTO playlist_collaborators (playlist_id, user_id, joined_at) VALUES (:playlistId, :userId, :at)
            ON CONFLICT DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(@Param("playlistId") UUID playlistId, @Param("userId") UUID userId, @Param("at") Instant at);

    @Modifying
    @Query(value = "DELETE FROM playlist_collaborators WHERE playlist_id = :playlistId AND user_id = :userId", nativeQuery = true)
    int remove(@Param("playlistId") UUID playlistId, @Param("userId") UUID userId);

    @Modifying
    @Query(value = "DELETE FROM playlist_collaborators WHERE playlist_id = :playlistId", nativeQuery = true)
    int removeAll(@Param("playlistId") UUID playlistId);

    @Query("select count(c) > 0 from PlaylistCollaborator c where c.id.playlistId = :playlistId and c.id.userId = :userId")
    boolean isCollaborator(@Param("playlistId") UUID playlistId, @Param("userId") UUID userId);

    long countByIdPlaylistId(UUID playlistId);

    @Query("select c from PlaylistCollaborator c where c.id.playlistId = :playlistId order by c.joinedAt, c.id.userId")
    List<PlaylistCollaborator> findByPlaylist(@Param("playlistId") UUID playlistId, Limit limit);
}
