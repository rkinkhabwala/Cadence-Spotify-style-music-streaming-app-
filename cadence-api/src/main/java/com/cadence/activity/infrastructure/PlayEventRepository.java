package com.cadence.activity.infrastructure;

import com.cadence.activity.domain.PlayEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlayEventRepository extends JpaRepository<PlayEvent, UUID> {

    /** Race-free first report: concurrent reports of a new playback insert it once. @return 1 if inserted */
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            INSERT INTO play_events (id, user_id, track_id, started_at, ms_played, source, source_id, completed, skipped,
                                     counted_at, updated_at, session_id, recommendation_id, rec_position)
            VALUES (:#{#p.id}, :#{#p.userId}, :#{#p.trackId}, :#{#p.startedAt}, :#{#p.msPlayed}, :#{#p.source.name()},
                    :#{#p.sourceId}, :#{#p.completed}, :#{#p.skipped}, :#{#p.countedAt}, :#{#p.updatedAt},
                    :#{#p.sessionId}, :#{#p.recommendationId}, :#{#p.recPosition})
            ON CONFLICT (id) DO NOTHING
            """)
    int insertIfAbsent(@Param("p") PlayEvent play);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PlayEvent p where p.id = :id")
    Optional<PlayEvent> lockById(@Param("id") UUID id);

    interface TopTrackRow {
        UUID getTrackId();

        long getPlays();

        Instant getLastPlayed();
    }

    /** Tracks by number of counted plays since {@code since}; ties: most recently played first. */
    @Query(nativeQuery = true, value = """
            SELECT track_id AS trackId, count(*) AS plays, max(started_at) AS lastPlayed
            FROM play_events
            WHERE user_id = :userId AND counted_at IS NOT NULL AND counted_at >= :since
            GROUP BY track_id
            ORDER BY plays DESC, lastPlayed DESC, track_id
            LIMIT :limit OFFSET :offset
            """)
    List<TopTrackRow> topTracks(@Param("userId") UUID userId, @Param("since") Instant since,
                                @Param("limit") int limit, @Param("offset") long offset);
}
