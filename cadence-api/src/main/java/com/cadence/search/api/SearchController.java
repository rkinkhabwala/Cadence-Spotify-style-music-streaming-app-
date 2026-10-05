package com.cadence.search.api;

import com.cadence.common.pagination.CursorRequest;
import com.cadence.common.security.CurrentUser;
import com.cadence.common.web.ApiPaths;
import com.cadence.search.application.SearchService;
import com.cadence.search.application.SearchViews.SearchResults;
import com.cadence.search.application.SearchViews.Suggestions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Search (authenticated; 30 requests/s per user across both endpoints). */
@RestController
@RequestMapping(ApiPaths.V1 + "/search")
@Tag(name = "Search")
class SearchController {

    private final SearchService search;

    SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping
    @Operation(summary = "Fuzzy, prefix-aware search grouped by type",
            description = "types: comma-separated subset of track,artist,album,playlist (default all). limit applies per "
                    + "type. Each group has its own nextCursor; paging with a cursor requires exactly one type.")
    SearchResults search(CurrentUser user, @RequestParam(required = false) String q,
                         @RequestParam(required = false) String types,
                         @RequestParam(required = false) Integer limit,
                         @RequestParam(required = false) String cursor) {
        return search.search(user.id(), q, types, CursorRequest.of(limit, cursor));
    }

    @GetMapping("/suggest")
    @Operation(summary = "Search-as-you-type suggestions across all types (best 8)")
    Suggestions suggest(CurrentUser user, @RequestParam(required = false) String q) {
        return search.suggest(user.id(), q);
    }
}
