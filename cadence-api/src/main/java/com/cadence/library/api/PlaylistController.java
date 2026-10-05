package com.cadence.library.api;

import com.cadence.common.pagination.CursorPage;
import com.cadence.common.pagination.CursorRequest;
import com.cadence.common.security.CurrentUser;
import com.cadence.common.web.ApiPaths;
import com.cadence.common.web.ETags;
import com.cadence.library.api.LibraryDtos.AddTracks;
import com.cadence.library.api.LibraryDtos.CreatePlaylist;
import com.cadence.library.api.LibraryDtos.RemoveTracks;
import com.cadence.library.api.LibraryDtos.Reorder;
import com.cadence.library.api.LibraryDtos.UpdatePlaylist;
import com.cadence.library.application.LibraryViews.PlaylistDetail;
import com.cadence.library.application.LibraryViews.PlaylistView;
import com.cadence.library.application.PlaylistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * Playlists. Responses carry {@code ETag: "<version>"}. PATCH requires {@code If-Match} (428 without, 412 on
 * mismatch); track changes accept an optional {@code If-Match}.
 */
@RestController
@RequestMapping(ApiPaths.V1)
@Tag(name = "Playlists")
class PlaylistController {

    private final PlaylistService playlists;

    PlaylistController(PlaylistService playlists) {
        this.playlists = playlists;
    }

    @GetMapping("/me/playlists")
    @Operation(summary = "The caller's playlists, newest first")
    CursorPage<PlaylistView> mine(CurrentUser user, @RequestParam(required = false) Integer limit,
                                  @RequestParam(required = false) String cursor) {
        return playlists.mine(user.id(), CursorRequest.of(limit, cursor));
    }

    @PostMapping("/playlists")
    @Operation(summary = "Create a playlist (PRIVATE unless visibility=PUBLIC); not idempotent")
    ResponseEntity<PlaylistView> create(CurrentUser user, @Valid @RequestBody CreatePlaylist body) {
        PlaylistView view = playlists.create(user.id(), body.name(), body.description(), body.visibility(), body.collaborative());
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/playlists/" + view.id()))
                .eTag(ETags.of(view.version())).body(view);
    }

    @GetMapping("/playlists/{id}")
    @Operation(summary = "Playlist with a page of its tracks in playlist order")
    ResponseEntity<PlaylistDetail> get(CurrentUser user, @PathVariable UUID id,
                                       @RequestParam(required = false) Integer limit,
                                       @RequestParam(required = false) String cursor) {
        PlaylistDetail detail = playlists.get(user.id(), id, CursorRequest.of(limit, cursor));
        return ResponseEntity.ok().eTag(ETags.of(detail.version())).body(detail);
    }

    @PatchMapping("/playlists/{id}")
    @Operation(summary = "Rename / describe / change visibility (owner only, If-Match required)")
    ResponseEntity<PlaylistView> update(CurrentUser user, @PathVariable UUID id,
                                        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                        @Valid @RequestBody UpdatePlaylist body) {
        PlaylistView view = playlists.update(user.id(), id, ETags.requireIfMatch(ifMatch), body.name(), body.description(),
                body.visibility(), body.collaborative());
        return withETag(view);
    }

    @DeleteMapping("/playlists/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a playlist (owner only)")
    void delete(CurrentUser user, @PathVariable UUID id) {
        playlists.delete(user.id(), id);
    }

    @PostMapping("/playlists/{id}/tracks")
    @Operation(summary = "Add READY tracks at a 0-based position (default: end); tracks already present are skipped")
    ResponseEntity<PlaylistView> addTracks(CurrentUser user, @PathVariable UUID id,
                                           @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                           @Valid @RequestBody AddTracks body) {
        return withETag(playlists.addTracks(user.id(), id, ETags.parseIfMatch(ifMatch), body.trackIds(), body.position()));
    }

    @DeleteMapping("/playlists/{id}/tracks")
    @Operation(summary = "Remove tracks (idempotent)")
    ResponseEntity<PlaylistView> removeTracks(CurrentUser user, @PathVariable UUID id,
                                              @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                              @Valid @RequestBody RemoveTracks body) {
        return withETag(playlists.removeTracks(user.id(), id, ETags.parseIfMatch(ifMatch), body.trackIds()));
    }

    @PutMapping("/playlists/{id}/tracks/reorder")
    @Operation(summary = "Move a track right after afterTrackId (null = to the top)")
    ResponseEntity<PlaylistView> reorder(CurrentUser user, @PathVariable UUID id,
                                         @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                         @Valid @RequestBody Reorder body) {
        return withETag(playlists.reorder(user.id(), id, ETags.parseIfMatch(ifMatch), body.trackId(), body.afterTrackId()));
    }

    private static ResponseEntity<PlaylistView> withETag(PlaylistView view) {
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }
}
