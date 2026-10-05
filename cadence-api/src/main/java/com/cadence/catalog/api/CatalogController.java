package com.cadence.catalog.api;

import com.cadence.catalog.application.CatalogReadService;
import com.cadence.catalog.application.CatalogViews.AlbumDetail;
import com.cadence.catalog.application.CatalogViews.AlbumView;
import com.cadence.catalog.application.CatalogViews.ArtistDetail;
import com.cadence.catalog.application.CatalogViews.GenreView;
import com.cadence.catalog.application.CatalogViews.TrackDetail;
import com.cadence.common.pagination.CursorPage;
import com.cadence.common.pagination.CursorRequest;
import com.cadence.common.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Public catalog reads (no authentication required). */
@RestController
@RequestMapping(ApiPaths.V1)
@Tag(name = "Catalog")
class CatalogController {

    private final CatalogReadService catalog;

    CatalogController(CatalogReadService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/artists/{id}")
    @Operation(summary = "Artist with their top 10 tracks")
    ArtistDetail artist(@PathVariable UUID id) {
        return catalog.artist(id);
    }

    @GetMapping("/artists/{id}/albums")
    @Operation(summary = "Artist's albums, newest first (cursor-paginated)")
    CursorPage<AlbumView> artistAlbums(@PathVariable UUID id, @RequestParam(required = false) Integer limit,
                                       @RequestParam(required = false) String cursor) {
        return catalog.artistAlbums(id, CursorRequest.of(limit, cursor));
    }

    @GetMapping("/albums/{id}")
    @Operation(summary = "Album with its tracklist")
    AlbumDetail album(@PathVariable UUID id) {
        return catalog.album(id);
    }

    @GetMapping("/tracks/{id}")
    @Operation(summary = "Track detail (READY tracks only)")
    TrackDetail track(@PathVariable UUID id) {
        return catalog.track(id);
    }

    @GetMapping("/genres")
    @Operation(summary = "Genres, alphabetical (cursor-paginated)")
    CursorPage<GenreView> genres(@RequestParam(required = false) Integer limit,
                                 @RequestParam(required = false) String cursor) {
        return catalog.genres(CursorRequest.of(limit, cursor));
    }
}
