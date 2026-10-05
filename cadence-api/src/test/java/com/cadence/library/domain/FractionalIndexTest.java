package com.cadence.library.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FractionalIndexTest {

    @Test
    void knownSequences() {
        assertThat(FractionalIndex.between(null, null)).isEqualTo("a0");
        assertThat(FractionalIndex.between("a0", null)).isEqualTo("a1");
        assertThat(FractionalIndex.between("a1", null)).isEqualTo("a2");
        assertThat(FractionalIndex.between("a0", "a1")).isEqualTo("a0V");
        assertThat(FractionalIndex.between("a1", "a2")).isEqualTo("a1V");
        assertThat(FractionalIndex.between("a0V", "a1")).isEqualTo("a0l");
        assertThat(FractionalIndex.between("Zz", "a0")).isEqualTo("ZzV");
        assertThat(FractionalIndex.between(null, "a0")).isEqualTo("Zz");
        assertThat(FractionalIndex.between("az", null)).isEqualTo("b00");
        assertThat(FractionalIndex.between("a0", "a0V")).isEqualTo("a0G");
        assertThat(FractionalIndex.between("b125", "b129")).isEqualTo("b127");
    }

    @Test
    void tenThousandAppendsStayShortAndSorted() {
        List<String> keys = FractionalIndex.between(null, null, 10_000);

        assertThat(keys).isSorted().doesNotHaveDuplicates();
        assertThat(keys).allMatch(k -> k.length() <= 4); // a0..az, b00..bzz, c000..
    }

    @Test
    void bulkInsertBetweenNeighboursIsOrderedAndBounded() {
        List<String> keys = FractionalIndex.between("a0", "a1", 500);

        assertThat(keys).hasSize(500).isSorted().doesNotHaveDuplicates()
                .allMatch(k -> k.compareTo("a0") > 0 && k.compareTo("a1") < 0);
        assertThat(keys).allMatch(k -> k.length() < 12);
        assertThat(FractionalIndex.between(null, "a0", 3)).isSorted().allMatch(k -> k.compareTo("a0") < 0);
    }

    @Test
    void randomInsertsAndMovesKeepAStrictOrder() {
        Random random = new Random(42);
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            int at = random.nextInt(keys.size() + 1);
            String before = at == 0 ? null : keys.get(at - 1);
            String after = at == keys.size() ? null : keys.get(at);
            String key = FractionalIndex.between(before, after);
            FractionalIndex.validateKey(key);
            keys.add(at, key);
            if (!keys.isEmpty() && random.nextInt(4) == 0) { // move a random item to a random place
                String moved = keys.remove(random.nextInt(keys.size()));
                int to = random.nextInt(keys.size() + 1);
                keys.add(to, FractionalIndex.between(to == 0 ? null : keys.get(to - 1), to == keys.size() ? null : keys.get(to)));
                assertThat(moved).isNotNull();
            }
        }
        assertThat(keys).isSorted().doesNotHaveDuplicates();
        assertThat(keys).allMatch(k -> k.length() < 40);
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> FractionalIndex.between("a1", "a0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FractionalIndex.between("a1", "a1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FractionalIndex.between("a10", null)).isInstanceOf(IllegalArgumentException.class); // trailing zero
        assertThatThrownBy(() -> FractionalIndex.between("!x", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FractionalIndex.between("c1", null)).isInstanceOf(IllegalArgumentException.class); // too short
    }
}
