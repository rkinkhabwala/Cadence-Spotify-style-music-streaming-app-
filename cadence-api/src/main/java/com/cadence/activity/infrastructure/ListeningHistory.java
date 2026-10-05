package com.cadence.activity.infrastructure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read model {@code listening_history}: one row per (user, track) with the latest play. */
@Repository
public class ListeningHistory {

    public record Entry(UUID trackId, Instant lastPlayedAt) {
    }

    private final JdbcClient jdbc;

    public ListeningHistory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Keeps the latest time; an older play of the same track never moves it back. */
    public void recordPlay(UUID userId, UUID trackId, Instant playedAt) {
        jdbc.sql("""
                        INSERT INTO listening_history (user_id, track_id, last_played_at) VALUES (:u, :t, :at)
                        ON CONFLICT (user_id, track_id)
                        DO UPDATE SET last_played_at = GREATEST(listening_history.last_played_at, EXCLUDED.last_played_at)
                        """)
                .param("u", userId).param("t", trackId).param("at", java.sql.Timestamp.from(playedAt)).update();
    }

    /** Distinct tracks, most recently played first. */
    public List<Entry> latest(UUID userId, int limit) {
        return jdbc.sql("""
                        SELECT track_id, last_played_at FROM listening_history
                        WHERE user_id = :u ORDER BY last_played_at DESC, track_id DESC LIMIT :limit
                        """)
                .param("u", userId).param("limit", limit)
                .query((rs, i) -> new Entry(rs.getObject("track_id", UUID.class), rs.getTimestamp("last_played_at").toInstant()))
                .list();
    }
}
