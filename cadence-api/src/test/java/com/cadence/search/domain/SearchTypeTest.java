package com.cadence.search.domain;

import com.cadence.common.error.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchTypeTest {

    @Test
    void blankMeansAllTypes() {
        assertThat(SearchType.parse(null)).isEqualTo(EnumSet.allOf(SearchType.class));
        assertThat(SearchType.parse(" ")).isEqualTo(EnumSet.allOf(SearchType.class));
        assertThat(SearchType.parse(",")).isEqualTo(EnumSet.allOf(SearchType.class));
    }

    @Test
    void parsesCommaSeparatedNamesCaseInsensitively() {
        assertThat(SearchType.parse("Track, album,track")).containsExactly(SearchType.TRACK, SearchType.ALBUM);
        assertThat(SearchType.ALBUM.paramName()).isEqualTo("album");
    }

    @Test
    void rejectsUnknownNames() {
        assertThatThrownBy(() -> SearchType.parse("track,podcast"))
                .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.code()).isEqualTo("invalid-search-type"))
                .hasMessageContaining("podcast");
    }
}
