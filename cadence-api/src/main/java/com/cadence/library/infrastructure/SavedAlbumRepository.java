package com.cadence.library.infrastructure;

import com.cadence.library.domain.SavedAlbum;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SavedAlbumRepository extends JpaRepository<SavedAlbum, SavedAlbum.Key> {

    @Modifying
    @Query(value = "INSERT INTO saved_albums (user_id, album_id, saved_at) VALUES (:userId, :albumId, :at) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int save(@Param("userId") UUID userId, @Param("albumId") UUID albumId, @Param("at") Instant at);

    @Modifying
    @Query(value = "DELETE FROM saved_albums WHERE user_id = :userId AND album_id = :albumId", nativeQuery = true)
    int unsave(@Param("userId") UUID userId, @Param("albumId") UUID albumId);

    @Query("select s from SavedAlbum s where s.id.userId = :userId order by s.savedAt desc, s.id.albumId desc")
    List<SavedAlbum> firstPage(@Param("userId") UUID userId, Limit limit);

    @Query("""
            select s from SavedAlbum s where s.id.userId = :userId
              and (s.savedAt < :at or (s.savedAt = :at and s.id.albumId < :albumId))
            order by s.savedAt desc, s.id.albumId desc""")
    List<SavedAlbum> pageAfter(@Param("userId") UUID userId, @Param("at") Instant at, @Param("albumId") UUID albumId,
                               Limit limit);
}
