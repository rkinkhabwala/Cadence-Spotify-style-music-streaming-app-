package com.cadence.activity.domain;

import com.cadence.activity.domain.FallbackRanking.Pick;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackRankingTest {

    private final UUID rock1 = UUID.randomUUID();
    private final UUID rock2 = UUID.randomUUID();
    private final UUID jazz1 = UUID.randomUUID();
    private final UUID jazz2 = UUID.randomUUID();
    private final UUID untagged = UUID.randomUUID();
    private final Map<UUID, List<String>> genres = Map.of(rock1, List.of("Rock"), rock2, List.of("Rock"),
            jazz1, List.of("Jazz", "Soul"), jazz2, List.of("jazz"));

    @Test
    void topGenresAreTheThreeMostPlayedTiesByName() {
        assertThat(FallbackRanking.topGenres(Map.of("Rock", 5L, "Jazz", 9L, "Soul", 5L, "Ambient", 1L, "Blues", 5L)))
                .containsExactly("jazz", "blues", "rock");
        assertThat(FallbackRanking.genreWeights(Map.of(jazz1, 3L, rock1, 2L, untagged, 7L), genres))
                .isEqualTo(Map.of("Jazz", 3L, "Soul", 3L, "Rock", 2L));
    }

    @Test
    void popularTracksInTheUsersGenresComeFirstThenTheRestOfThePopularList() {
        List<Pick> picks = FallbackRanking.rank(List.of(rock1, jazz1, untagged, rock2, jazz2, jazz1), genres,
                Set.of("jazz"), Set.of(), 10);

        assertThat(picks).extracting(Pick::trackId).containsExactly(jazz1, jazz2, rock1, untagged, rock2);
        assertThat(picks).extracting(Pick::reason).containsExactly("POPULAR_IN_YOUR_GENRES", "POPULAR_IN_YOUR_GENRES",
                "POPULAR", "POPULAR", "POPULAR");
    }

    @Test
    void excludedTracksAreSkippedAndTheLimitHolds() {
        assertThat(FallbackRanking.rank(List.of(rock1, jazz1, jazz2, rock2), genres, Set.of("jazz"), Set.of(jazz1), 2))
                .extracting(Pick::trackId).containsExactly(jazz2, rock1);
        assertThat(FallbackRanking.rank(List.of(rock1, rock2), genres, Set.of(), Set.of(), 5))
                .as("no taste yet: plain popularity").extracting(Pick::trackId).containsExactly(rock1, rock2);
        assertThat(FallbackRanking.rank(List.of(), genres, Set.of("jazz"), Set.of(), 5)).isEmpty();
    }
}
