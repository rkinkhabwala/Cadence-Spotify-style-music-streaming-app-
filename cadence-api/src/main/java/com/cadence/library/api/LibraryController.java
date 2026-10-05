package com.cadence.library.api;

import com.cadence.common.pagination.CursorPage;
import com.cadence.common.pagination.CursorRequest;
import com.cadence.common.security.CurrentUser;
import com.cadence.common.web.ApiPaths;
import com.cadence.library.application.LibraryService;
import com.cadence.library.application.LibraryViews.FollowedArtistView;
import com.cadence.library.application.LibraryViews.LikedTrackView;
import com.cadence.library.application.LibraryViews.SavedAlbumView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Likes, follows and saved albums; PUT/DELETE are idempotent (204). */
@RestController
@RequestMapping(ApiPaths.V1 + "/me")
@Tag(name = "Library")
class LibraryController {

    private final LibraryService library;

    LibraryController(LibraryService library) {
        this.library = library;
    }

    @PutMapping("/likes/tracks/{trackId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Like a track (idempotent)")
    void like(CurrentUser user, @PathVariable UUID trackId) {
        library.like(user.id(), trackId);
    }

    @DeleteMapping("/likes/tracks/{trackId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Unlike a track (idempotent)")
    void unlike(CurrentUser user, @PathVariable UUID trackId) {
        library.unlike(user.id(), trackId);
    }

    @GetMapping("/likes/tracks")
    @Operation(summary = "Liked tracks, most recent first")
    CursorPage<LikedTrackView> likes(CurrentUser user, @RequestParam(required = false) Integer limit,
                                     @RequestParam(required = false) String cursor) {
        return library.likedTracks(user.id(), CursorRequest.of(limit, cursor));
    }

    @PutMapping("/following/artists/{artistId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Follow an artist (idempotent)")
    void follow(CurrentUser user, @PathVariable UUID artistId) {
        library.follow(user.id(), artistId);
    }

    @DeleteMapping("/following/artists/{artistId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Unfollow an artist (idempotent)")
    void unfollow(CurrentUser user, @PathVariable UUID artistId) {
        library.unfollow(user.id(), artistId);
    }

    @GetMapping("/following/artists")
    @Operation(summary = "Followed artists, most recently followed first")
    CursorPage<FollowedArtistView> following(CurrentUser user, @RequestParam(required = false) Integer limit,
                                             @RequestParam(required = false) String cursor) {
        return library.followedArtists(user.id(), CursorRequest.of(limit, cursor));
    }

    @PutMapping("/albums/{albumId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Save an album to the library (idempotent)")
    void saveAlbum(CurrentUser user, @PathVariable UUID albumId) {
        library.saveAlbum(user.id(), albumId);
    }

    @DeleteMapping("/albums/{albumId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a saved album (idempotent)")
    void unsaveAlbum(CurrentUser user, @PathVariable UUID albumId) {
        library.unsaveAlbum(user.id(), albumId);
    }

    @GetMapping("/albums")
    @Operation(summary = "Saved albums, most recent first")
    CursorPage<SavedAlbumView> savedAlbums(CurrentUser user, @RequestParam(required = false) Integer limit,
                                           @RequestParam(required = false) String cursor) {
        return library.savedAlbums(user.id(), CursorRequest.of(limit, cursor));
    }
}
