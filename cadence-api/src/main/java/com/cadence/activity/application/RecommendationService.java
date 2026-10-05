package com.cadence.activity.application;

import com.cadence.activity.application.ActivityViews.RecommendedTrack;
import com.cadence.activity.application.ActivityViews.Recommendations;
import com.cadence.activity.domain.FallbackRanking;
import com.cadence.activity.infrastructure.PlayEventRepository;
import com.cadence.activity.infrastructure.RecommendationCache;
import com.cadence.activity.infrastructure.RecommenderClient;
import com.cadence.activity.infrastructure.RecommenderProperties;
import com.cadence.activity.infrastructure.TrackStatsStore;
import com.cadence.catalog.CatalogQueries;
import com.cadence.catalog.TrackSummary;
import com.cadence.library.LibraryQueries;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Recommendations (spec 3.5, D90). Cadence computes none itself: it asks the recommender, hydrates the returned ids
 * from the catalog, drops tracks that aren't READY or that the user already likes, and caches the recommender's
 * answer per user for 10 minutes. If the recommender is disabled, down, slow or has nothing usable, the fallback
 * serves popular tracks from the genres the user plays most.
 *
 * <p>Deliberately not transactional: no database connection is held while waiting for the recommender.
 */
@Service
public class RecommendationService {

    public static final String SOURCE_RECOMMENDER = "recommender";
    public static final String SOURCE_FALLBACK = "fallback";
    static final String CONTEXT_HOME = "home";
    static final String CONTEXT_RADIO = "radio";
    /** Plays considered for the user's genres in the fallback. */
    static final Duration TASTE_WINDOW = Duration.ofDays(90);
    static final int FALLBACK_CANDIDATES = 200;

    private final RecommenderClient recommender;
    private final RecommendationCache cache;
    private final RecommenderProperties props;
    private final CatalogQueries catalog;
    private final LibraryQueries library;
    private final TrackStatsStore stats;
    private final PlayEventRepository plays;
    private final MeterRegistry meters;
    private final Clock clock;

    RecommendationService(RecommenderClient recommender, RecommendationCache cache, RecommenderProperties props,
                          CatalogQueries catalog, LibraryQueries library, TrackStatsStore stats,
                          PlayEventRepository plays, MeterRegistry meters, Clock clock) {
        this.recommender = recommender;
        this.cache = cache;
        this.props = props;
        this.catalog = catalog;
        this.library = library;
        this.stats = stats;
        this.plays = plays;
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * @param seedTrackId null for "made for you" (recommender context {@code home}); a track for "because you
     *                    listened to X" (context {@code radio} seeded with it; the seed itself is never returned)
     */
    public Recommendations recommend(UUID userId, int limit, UUID seedTrackId) {
        String context = seedTrackId == null ? CONTEXT_HOME : CONTEXT_RADIO;
        Optional<Recommendations> fromRecommender = fromRecommender(userId, context, seedTrackId, limit);
        Recommendations result = fromRecommender.orElseGet(() -> fallback(userId, limit, seedTrackId));
        meters.counter("cadence.recommendations.served", "source", result.source(), "context", context).increment();
        return result;
    }

    private Optional<Recommendations> fromRecommender(UUID userId, String context, UUID seed, int limit) {
        if (!recommender.enabled()) {
            return Optional.empty();
        }
        Optional<RecommenderClient.Result> answer = cache.get(userId, context, seed).or(() -> {
            Optional<RecommenderClient.Result> fresh = recommender.recommend(
                    new RecommenderClient.Request(userId, context, props.fetchSize(), seed));
            fresh.filter(r -> !r.items().isEmpty()).ifPresent(r -> cache.put(userId, context, seed, r));
            return fresh;
        });
        if (answer.isEmpty() || answer.get().items().isEmpty()) {
            return Optional.empty();
        }
        RecommenderClient.Result result = answer.get();
        Map<UUID, RecommenderClient.Item> byTrack = new LinkedHashMap<>();
        result.items().stream().filter(i -> !i.trackId().equals(seed)).forEach(i -> byTrack.putIfAbsent(i.trackId(), i));
        Map<UUID, TrackSummary> tracks = catalog.findTracks(byTrack.keySet());
        Set<UUID> liked = library.likedAmong(userId, byTrack.keySet());
        List<RecommendedTrack> items = byTrack.values().stream()
                .filter(i -> tracks.containsKey(i.trackId()) && tracks.get(i.trackId()).isPlayable())
                .filter(i -> !liked.contains(i.trackId()))
                .limit(limit)
                .map(i -> new RecommendedTrack(tracks.get(i.trackId()), i.reasonCode(), i.position()))
                .toList();
        return items.isEmpty() ? Optional.empty()
                : Optional.of(new Recommendations(items, SOURCE_RECOMMENDER, result.recommendationId(), null));
    }

    private Recommendations fallback(UUID userId, int limit, UUID seed) {
        Map<UUID, Long> taste = seed != null ? Map.of(seed, 1L)
                : plays.topTracks(userId, clock.instant().minus(TASTE_WINDOW), 100, 0).stream()
                .collect(Collectors.toMap(PlayEventRepository.TopTrackRow::getTrackId,
                        PlayEventRepository.TopTrackRow::getPlays));
        Set<String> topGenres = FallbackRanking.topGenres(FallbackRanking.genreWeights(taste, catalog.genresOf(taste.keySet())));
        // popular in the user's genres: 30-day plays first, then all-time play count; then everything popular
        List<UUID> inGenres = catalog.popularTrackIdsInGenres(topGenres, FALLBACK_CANDIDATES);
        Map<UUID, Long> recentPlays = stats.plays30d(inGenres);
        List<UUID> candidates = Stream.of(
                        inGenres.stream().sorted(Comparator.comparing((UUID id) -> recentPlays.getOrDefault(id, 0L)).reversed()),
                        stats.mostPlayed(FALLBACK_CANDIDATES).stream().map(TrackStatsStore.TrackStat::trackId),
                        catalog.popularTrackIds(FALLBACK_CANDIDATES).stream())
                .flatMap(s -> s).distinct().toList();
        Map<UUID, List<String>> genres = catalog.genresOf(candidates);
        Set<UUID> excluded = new HashSet<>(library.likedAmong(userId, candidates));
        if (seed != null) {
            excluded.add(seed);
        }
        // over-pick: stats may still list tracks that have since left READY
        List<FallbackRanking.Pick> picks = FallbackRanking.rank(candidates, genres, topGenres, excluded, limit * 2);
        Map<UUID, TrackSummary> tracks = catalog.findTracks(picks.stream().map(FallbackRanking.Pick::trackId).toList());
        List<RecommendedTrack> items = new ArrayList<>();
        for (FallbackRanking.Pick pick : picks) {
            TrackSummary track = tracks.get(pick.trackId());
            if (track != null && track.isPlayable() && items.size() < limit) {
                items.add(new RecommendedTrack(track, pick.reason(), items.size()));
            }
        }
        return new Recommendations(List.copyOf(items), SOURCE_FALLBACK, null, null);
    }
}
