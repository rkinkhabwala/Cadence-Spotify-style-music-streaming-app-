package com.cadence.activity.application;

import com.cadence.activity.application.ActivityViews.RecentlyPlayedItem;
import com.cadence.activity.application.ActivityViews.TopTrackItem;
import com.cadence.activity.domain.TopRange;
import com.cadence.activity.infrastructure.ListeningHistory;
import com.cadence.activity.infrastructure.PlayEventRepository;
import com.cadence.activity.infrastructure.PlayEventRepository.TopTrackRow;
import com.cadence.catalog.CatalogQueries;
import com.cadence.catalog.TrackSummary;
import com.cadence.common.pagination.Cursor;
import com.cadence.common.pagination.CursorPage;
import com.cadence.common.pagination.CursorRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ActivityQueries {

    /** Spec 5: recently played shows the last 50 (distinct) tracks. */
    public static final int RECENTLY_PLAYED_MAX = 50;
    static final int TOP_TRACKS_MAX = 50;

    private final ListeningHistory history;
    private final PlayEventRepository plays;
    private final CatalogQueries catalog;
    private final Clock clock;

    ActivityQueries(ListeningHistory history, PlayEventRepository plays, CatalogQueries catalog, Clock clock) {
        this.history = history;
        this.plays = plays;
        this.catalog = catalog;
        this.clock = clock;
    }

    /** Distinct tracks, most recent first. Tracks deleted from the catalog are left out. */
    public List<RecentlyPlayedItem> recentlyPlayed(UUID userId, Integer limit) {
        int effective = limit == null ? RECENTLY_PLAYED_MAX : Math.clamp(limit, 1, RECENTLY_PLAYED_MAX);
        List<ListeningHistory.Entry> entries = history.latest(userId, effective);
        Map<UUID, TrackSummary> tracks = catalog.findTracks(entries.stream().map(ListeningHistory.Entry::trackId).toList());
        return entries.stream().filter(e -> tracks.containsKey(e.trackId()))
                .map(e -> new RecentlyPlayedItem(tracks.get(e.trackId()), tracks.get(e.trackId()).isPlayable(), e.lastPlayedAt()))
                .toList();
    }

    /** Most-streamed tracks in the range; the cursor is an offset (up to 50 entries in total). */
    public CursorPage<TopTrackItem> topTracks(UUID userId, TopRange range, CursorRequest page) {
        long offset = page.isFirstPage() ? 0 : Math.max(0, page.cursor().longValue(0));
        int limit = (int) Math.max(0, Math.min(page.limit(), TOP_TRACKS_MAX - offset));
        if (limit == 0) {
            return new CursorPage<>(List.of(), null);
        }
        List<TopTrackRow> rows = plays.topTracks(userId, range.since(clock.instant()), limit + 1, offset);
        boolean more = rows.size() > limit && offset + limit < TOP_TRACKS_MAX;
        List<TopTrackRow> pageRows = rows.subList(0, Math.min(limit, rows.size()));
        Map<UUID, TrackSummary> tracks = catalog.findTracks(pageRows.stream().map(TopTrackRow::getTrackId).toList());
        List<TopTrackItem> items = pageRows.stream().filter(r -> tracks.containsKey(r.getTrackId()))
                .map(r -> new TopTrackItem(tracks.get(r.getTrackId()), r.getPlays())).toList();
        return new CursorPage<>(items, more ? Cursor.of(offset + limit).encode() : null);
    }
}
