package com.cadence.activity.application;

import com.cadence.activity.infrastructure.TrackStatsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Rebuilds the 30-day popularity read model (spec 4 TrackStats) on a schedule. A full rebuild is one aggregate
 * query, cheap at this scale and idempotent, so several API instances can run it without coordination.
 */
@Service
public class TrackStatsService {

    static final Duration WINDOW = Duration.ofDays(30);
    private static final Logger log = LoggerFactory.getLogger(TrackStatsService.class);

    private final TrackStatsStore stats;
    private final Clock clock;

    TrackStatsService(TrackStatsStore stats, Clock clock) {
        this.stats = stats;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${cadence.activity.stats-initial-delay}",
            fixedDelayString = "${cadence.activity.stats-refresh-interval}")
    @Transactional
    public void refresh() {
        Instant now = clock.instant();
        int tracks = stats.rebuild(now.minus(WINDOW), now);
        log.debug("Track stats refreshed for {} tracks", tracks);
    }
}
