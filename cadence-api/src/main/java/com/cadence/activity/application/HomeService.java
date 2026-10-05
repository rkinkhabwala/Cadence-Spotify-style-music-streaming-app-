package com.cadence.activity.application;

import com.cadence.activity.application.ActivityViews.Home;
import com.cadence.activity.application.ActivityViews.RecentlyPlayedItem;
import com.cadence.activity.application.ActivityViews.Shelf;
import com.cadence.activity.application.ActivityViews.ShelfItem;
import com.cadence.activity.application.ActivityViews.TopTrackItem;
import com.cadence.activity.domain.TopRange;
import com.cadence.activity.infrastructure.TrackStatsStore;
import com.cadence.catalog.CatalogQueries;
import com.cadence.catalog.TrackSummary;
import com.cadence.common.pagination.CursorRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /home} (spec 5): recently played, the user's top tracks of the last 4 weeks, popular tracks (30-day
 * stats) and new releases. "Made for you" arrives with the recommender in Phase 3.
 */
@Service
@Transactional(readOnly = true)
public class HomeService {

    static final int SHELF_SIZE = 12;

    private final ActivityQueries activity;
    private final TrackStatsStore stats;
    private final CatalogQueries catalog;

    HomeService(ActivityQueries activity, TrackStatsStore stats, CatalogQueries catalog) {
        this.activity = activity;
        this.stats = stats;
        this.catalog = catalog;
    }

    public Home home(UUID userId) {
        List<Shelf> shelves = new ArrayList<>();
        add(shelves, "recently-played", "Recently played", activity.recentlyPlayed(userId, SHELF_SIZE).stream()
                .filter(RecentlyPlayedItem::playable).map(i -> ShelfItem.of(i.track())).toList());
        add(shelves, "top-tracks", "Your top tracks this month", activity.topTracks(userId, TopRange.SHORT,
                CursorRequest.firstPage(SHELF_SIZE)).items().stream().map(TopTrackItem::track)
                .filter(TrackSummary::isPlayable).map(ShelfItem::of).toList());
        add(shelves, "popular", "Popular right now", popular());
        add(shelves, "new-releases", "New releases", catalog.newReleases(SHELF_SIZE).stream().map(ShelfItem::of).toList());
        return new Home(shelves);
    }

    private List<ShelfItem> popular() {
        List<UUID> ids = stats.mostPlayed(SHELF_SIZE * 2).stream().map(TrackStatsStore.TrackStat::trackId).toList();
        Map<UUID, TrackSummary> tracks = catalog.findTracks(ids);
        return ids.stream().map(tracks::get).filter(t -> t != null && t.isPlayable()).limit(SHELF_SIZE)
                .map(ShelfItem::of).toList();
    }

    private static void add(List<Shelf> shelves, String id, String title, List<ShelfItem> items) {
        if (!items.isEmpty()) {
            shelves.add(new Shelf(id, title, items));
        }
    }
}
