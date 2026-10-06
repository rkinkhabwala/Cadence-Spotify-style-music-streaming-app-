package com.cadence.library.infrastructure;

import com.cadence.library.domain.Playlist;
import com.cadence.library.domain.Visibility;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PlaylistRepository extends JpaRepository<Playlist, UUID> {

    List<Playlist> findByVisibility(Visibility visibility);

    List<Playlist> findByOwnerIdOrderByIdDesc(UUID ownerId, Limit limit);

    @Query("select p from Playlist p where p.ownerId = :ownerId and p.id < :after order by p.id desc")
    List<Playlist> findByOwnerAfter(@Param("ownerId") UUID ownerId, @Param("after") UUID after, Limit limit);

    /** Playlists the user owns or collaborates on (while collaborative), newest first. */
    @Query("""
            select p from Playlist p
            where p.ownerId = :userId or (p.collaborative = true and exists (
                select 1 from PlaylistCollaborator c where c.id.playlistId = p.id and c.id.userId = :userId))
            order by p.id desc""")
    List<Playlist> findMine(@Param("userId") UUID userId, Limit limit);

    @Query("""
            select p from Playlist p
            where (p.ownerId = :userId or (p.collaborative = true and exists (
                select 1 from PlaylistCollaborator c where c.id.playlistId = p.id and c.id.userId = :userId)))
              and p.id < :after
            order by p.id desc""")
    List<Playlist> findMineAfter(@Param("userId") UUID userId, @Param("after") UUID after, Limit limit);
}
