package com.cadence.activity.application;

import com.cadence.activity.application.ActivityViews.PlayView;
import com.cadence.activity.domain.PlayEvent;
import com.cadence.activity.domain.PlaySource;
import com.cadence.activity.infrastructure.ListeningHistory;
import com.cadence.activity.infrastructure.PlayEventRepository;
import com.cadence.catalog.CatalogQueries;
import com.cadence.catalog.TrackSummary;
import com.cadence.common.error.NotFoundException;
import com.cadence.events.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Play reporting (spec 3.2 step 5). Idempotent per {@code playId}: the first report creates the playback, later
 * reports merge into it under a row lock, and a report that changes nothing writes nothing. Each real change updates
 * listening history and publishes {@code activity.track-played} in the same transaction.
 */
@Service
public class PlayService {

    public record ReportPlay(UUID playId, UUID trackId, int msPlayed, PlaySource source, UUID sourceId,
                             boolean completed, boolean skipped) {
    }

    private final PlayEventRepository plays;
    private final ListeningHistory history;
    private final CatalogQueries catalog;
    private final ActivityEvents events;
    private final Clock clock;

    PlayService(PlayEventRepository plays, ListeningHistory history, CatalogQueries catalog, ActivityEvents events,
                Clock clock) {
        this.plays = plays;
        this.history = history;
        this.catalog = catalog;
        this.events = events;
        this.clock = clock;
    }

    /** Without a {@code playId} every call is a new playback (not idempotent); clients should always send one. */
    @Transactional
    public PlayView report(UUID userId, ReportPlay report) {
        catalog.findTrack(report.trackId()).filter(TrackSummary::isPlayable)
                .orElseThrow(() -> new NotFoundException("Track", report.trackId()));
        Instant now = clock.instant();
        UUID playId = report.playId() != null ? report.playId() : UuidV7.generate();
        boolean created = plays.insertIfAbsent(new PlayEvent(playId, userId, report.trackId(), report.msPlayed(),
                report.source(), report.sourceId(), report.completed(), report.skipped(), now)) == 1;
        PlayEvent play = plays.lockById(playId).orElseThrow();
        boolean changed = created || play.report(userId, report.trackId(), report.msPlayed(), report.completed(),
                report.skipped(), now);
        if (changed) {
            plays.flush();
            history.recordPlay(userId, play.getTrackId(), play.getStartedAt());
            events.trackPlayed(play, now);
        }
        return new PlayView(play.getId(), play.getTrackId(), play.getMsPlayed(), play.isCompleted(), play.isSkipped(),
                play.isCounted(), play.getStartedAt());
    }
}
