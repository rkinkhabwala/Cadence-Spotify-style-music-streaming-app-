package com.cadence.library.infrastructure;

import com.cadence.library.domain.Playlist;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PlaylistRepository extends JpaRepository<Playlist, UUID> {

    List<Playlist> findByOwnerIdOrderByIdDesc(UUID ownerId, Limit limit);

    @Query("select p from Playlist p where p.ownerId = :ownerId and p.id < :after order by p.id desc")
    List<Playlist> findByOwnerAfter(@Param("ownerId") UUID ownerId, @Param("after") UUID after, Limit limit);
}
