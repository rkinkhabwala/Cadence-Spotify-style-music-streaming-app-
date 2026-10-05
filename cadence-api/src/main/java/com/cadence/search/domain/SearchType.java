package com.cadence.search.domain;

import com.cadence.common.error.BadRequestException;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Searchable entity types, in the order results are grouped ({@code ?types=track,artist,album,playlist}). */
public enum SearchType {
    TRACK("tracks"),
    ARTIST("artists"),
    ALBUM("albums"),
    PLAYLIST("playlists");

    private final String indexSuffix;

    SearchType(String indexSuffix) {
        this.indexSuffix = indexSuffix;
    }

    public String indexSuffix() {
        return indexSuffix;
    }

    /** The {@code types} query value: {@code track}, {@code artist}, ... */
    public String paramName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a comma-separated {@code types} parameter; blank means all types.
     *
     * @throws BadRequestException ({@code invalid-search-type}) for unknown names
     */
    public static Set<SearchType> parse(String types) {
        if (types == null || types.isBlank()) {
            return EnumSet.allOf(SearchType.class);
        }
        EnumSet<SearchType> parsed = EnumSet.noneOf(SearchType.class);
        for (String part : types.split(",")) {
            String name = part.strip();
            if (name.isEmpty()) {
                continue;
            }
            parsed.add(Arrays.stream(values()).filter(t -> t.paramName().equalsIgnoreCase(name)).findFirst()
                    .orElseThrow(() -> new BadRequestException("invalid-search-type", "Unknown type '" + name
                            + "'; use " + Arrays.stream(values()).map(SearchType::paramName).collect(Collectors.joining(", ")))));
        }
        if (parsed.isEmpty()) {
            return EnumSet.allOf(SearchType.class);
        }
        return parsed;
    }
}
