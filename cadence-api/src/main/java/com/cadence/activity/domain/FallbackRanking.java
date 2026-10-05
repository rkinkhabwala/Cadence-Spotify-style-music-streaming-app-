package com.cadence.activity.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The recommendation fallback (spec 3.5): popular tracks from the genres the user plays most. Candidates arrive in
 * popularity order; those sharing one of the user's top genres come first, then the rest of the popular list, so the
 * fallback is never empty while there are playable tracks.
 */
public final class FallbackRanking {

    public static final String POPULAR_IN_YOUR_GENRES = "POPULAR_IN_YOUR_GENRES";
    public static final String POPULAR = "POPULAR";
    static final int TOP_GENRES = 3;

    public record Pick(UUID trackId, String reason) {
    }

    private FallbackRanking() {
    }

    /** The user's top genres by weight (play count), ties broken by name, compared case-insensitively. */
    public static Set<String> topGenres(Map<String, Long> genreWeights) {
        Set<String> top = new LinkedHashSet<>();
        genreWeights.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_GENRES)
                .forEach(e -> top.add(e.getKey().toLowerCase(Locale.ROOT)));
        return top;
    }

    /**
     * @param popular     candidate track ids, most popular first (duplicates ignored)
     * @param genresOf    genres per candidate (missing = none)
     * @param topGenres   the user's top genres, lower case; empty = no preference
     * @param excluded    never returned (liked tracks, the seed track)
     */
    public static List<Pick> rank(List<UUID> popular, Map<UUID, List<String>> genresOf, Set<String> topGenres,
                                  Collection<UUID> excluded, int limit) {
        List<Pick> matching = new ArrayList<>();
        List<Pick> others = new ArrayList<>();
        Set<UUID> seen = new HashSet<>(excluded);
        for (UUID id : popular) {
            if (!seen.add(id)) {
                continue;
            }
            boolean inTopGenre = genresOf.getOrDefault(id, List.of()).stream()
                    .anyMatch(g -> topGenres.contains(g.toLowerCase(Locale.ROOT)));
            (inTopGenre ? matching : others).add(new Pick(id, inTopGenre ? POPULAR_IN_YOUR_GENRES : POPULAR));
        }
        List<Pick> ranked = new ArrayList<>(matching);
        ranked.addAll(others);
        return ranked.subList(0, Math.min(limit, ranked.size()));
    }

    /** Sums play counts per genre (a track counts once for each of its genres). */
    public static Map<String, Long> genreWeights(Map<UUID, Long> playsByTrack, Map<UUID, List<String>> genresOf) {
        Map<String, Long> weights = new HashMap<>();
        playsByTrack.forEach((track, plays) -> genresOf.getOrDefault(track, List.of())
                .forEach(g -> weights.merge(g, plays, Long::sum)));
        return weights;
    }
}
