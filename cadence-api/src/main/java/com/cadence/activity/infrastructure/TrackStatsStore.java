package com.cadence.activity.infrastructure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read model {@code track_stats} (spec 4 TrackStats): counted plays and unique listeners over the last 30 days. */
@Repository
public class TrackStatsStore {

    public record TrackStat(UUID trackId, long plays30d, long uniqueListeners30d) {
    }

    private final JdbcClient jdbc;

    public TrackStatsStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Recomputes every row from {@code play_events}; tracks without counted plays in the window are removed. */
    public int rebuild(Instant since, Instant now) {
        Timestamp refreshed = Timestamp.from(now);
        int rows = jdbc.sql("""
                        INSERT INTO track_stats (track_id, plays_30d, unique_listeners_30d, refreshed_at)
                        SELECT track_id, count(*), count(DISTINCT user_id), :now
                        FROM play_events WHERE counted_at IS NOT NULL AND counted_at >= :since
                        GROUP BY track_id
                        ON CONFLICT (track_id) DO UPDATE SET plays_30d = EXCLUDED.plays_30d,
                            unique_listeners_30d = EXCLUDED.unique_listeners_30d, refreshed_at = EXCLUDED.refreshed_at
                        """)
                .param("since", Timestamp.from(since)).param("now", refreshed).update();
        jdbc.sql("DELETE FROM track_stats WHERE refreshed_at < :now").param("now", refreshed).update();
        return rows;
    }

    public List<TrackStat> mostPlayed(int limit) {
        return jdbc.sql("""
                        SELECT track_id, plays_30d, unique_listeners_30d FROM track_stats
                        ORDER BY plays_30d DESC, unique_listeners_30d DESC, track_id LIMIT :limit
                        """)
                .param("limit", limit)
                .query((rs, i) -> new TrackStat(rs.getObject("track_id", UUID.class), rs.getLong("plays_30d"),
                        rs.getLong("unique_listeners_30d")))
                .list();
    }
}
