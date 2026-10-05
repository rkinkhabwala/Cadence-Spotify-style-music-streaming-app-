package com.cadence.activity.application;

import com.cadence.activity.application.ActivityViews.Home;
import com.cadence.activity.application.ActivityViews.Recommendations;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * {@code GET /home} (spec 5): recently played, "Made for you" and "Because you listened to X" (both from the
 * recommender, or its fallback; D91), the user's top tracks of the last 4 weeks, popular tracks (30-day stats) and new
 * releases. Not transactional, so no database connection waits on the recommender; the two recommendation shelves are
 * fetched in parallel on virtual threads.
 */
@Service
public class HomeService {

    static final int SHELF_SIZE = 12;

    private final ActivityQueries activity;
    private final TrackStatsStore stats;
    private final CatalogQueries catalog;
    private final RecommendationService recommendations;

    HomeService(ActivityQueries activity, TrackStatsStore stats, CatalogQueries catalog,
                RecommendationService recommendations) {
        this.activity = activity;
        this.stats = stats;
        this.catalog = catalog;
        this.recommendations = recommendations;
    }

    public Home home(UUID userId) {
        List<RecentlyPlayedItem> recent = activity.recentlyPlayed(userId, SHELF_SIZE).stream()
                .filter(RecentlyPlayedItem::playable).toList();
        TrackSummary lastPlayed = recent.isEmpty() ? null : recent.getFirst().track();
        Recommendations madeForYou;
        Recommendations becauseYouListened = null;
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Recommendations> forYou = threads.submit(() -> recommendations.recommend(userId, SHELF_SIZE, null));
            Future<Recommendations> similar = lastPlayed == null ? null
                    : threads.submit(() -> recommendations.recommend(userId, SHELF_SIZE, lastPlayed.id()));
            madeForYou = forYou.get();
            if (similar != null) {
                becauseYouListened = similar.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
        }

        List<Shelf> shelves = new ArrayList<>();
        add(shelves, "recently-played", "Recently played", recent.stream().map(i -> ShelfItem.of(i.track())).toList());
        addRecommended(shelves, "made-for-you", "Made for you", madeForYou);
        if (becauseYouListened != null) {
            addRecommended(shelves, "because-you-listened", "Because you listened to " + lastPlayed.title(),
                    becauseYouListened);
        }
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

    private static void addRecommended(List<Shelf> shelves, String id, String title, Recommendations recommended) {
        if (!recommended.items().isEmpty()) {
            shelves.add(new Shelf(id, title, recommended.items().stream().map(ShelfItem::of).toList(),
                    recommended.source(), recommended.recommendationId()));
        }
    }

    private static void add(List<Shelf> shelves, String id, String title, List<ShelfItem> items) {
        if (!items.isEmpty()) {
            shelves.add(new Shelf(id, title, items));
        }
    }
}
