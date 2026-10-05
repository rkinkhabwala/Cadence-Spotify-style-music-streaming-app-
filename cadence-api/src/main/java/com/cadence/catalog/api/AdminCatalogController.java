package com.cadence.catalog.api;

import com.cadence.catalog.TrackStatus;
import com.cadence.catalog.api.CatalogDtos.CreateAlbum;
import com.cadence.catalog.api.CatalogDtos.CreateArtist;
import com.cadence.catalog.api.CatalogDtos.CreateTrack;
import com.cadence.catalog.api.CatalogDtos.CreditRequest;
import com.cadence.catalog.api.CatalogDtos.UpdateAlbum;
import com.cadence.catalog.api.CatalogDtos.UpdateArtist;
import com.cadence.catalog.api.CatalogDtos.UpdateTrack;
import com.cadence.catalog.api.CatalogDtos.UploadUrlRequest;
import com.cadence.catalog.application.AlbumAdminService;
import com.cadence.catalog.application.ArtistAdminService;
import com.cadence.catalog.application.CatalogViews.AdminTrackView;
import com.cadence.catalog.application.CatalogViews.AlbumView;
import com.cadence.catalog.application.CatalogViews.ArtistView;
import com.cadence.catalog.application.CatalogViews.UploadUrl;
import com.cadence.catalog.application.TrackAdminService;
import com.cadence.catalog.application.TrackAdminService.Credit;
import com.cadence.catalog.application.TrackUploadService;
import com.cadence.common.pagination.CursorPage;
import com.cadence.common.pagination.CursorRequest;
import com.cadence.common.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Catalog administration (role ADMIN; also enforced by the URL rule on /admin/**). POSTs that create entities are
 * not idempotent (each call creates a new entity); PATCH, DELETE and the upload steps are.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/admin")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin catalog")
class AdminCatalogController {

    private final ArtistAdminService artists;
    private final AlbumAdminService albums;
    private final TrackAdminService tracks;
    private final TrackUploadService uploads;

    AdminCatalogController(ArtistAdminService artists, AlbumAdminService albums, TrackAdminService tracks,
                           TrackUploadService uploads) {
        this.artists = artists;
        this.albums = albums;
        this.tracks = tracks;
        this.uploads = uploads;
    }

    // ---- artists

    @PostMapping("/artists")
    ResponseEntity<ArtistView> createArtist(@Valid @RequestBody CreateArtist body) {
        ArtistView view = artists.create(body.name(), body.bio(), body.imageUrl(), body.verified());
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/artists/" + view.id())).body(view);
    }

    @PatchMapping("/artists/{id}")
    ArtistView updateArtist(@PathVariable UUID id, @Valid @RequestBody UpdateArtist body) {
        return artists.update(id, body.name(), body.bio(), body.imageUrl(), body.verified());
    }

    @DeleteMapping("/artists/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteArtist(@PathVariable UUID id) {
        artists.delete(id);
    }

    // ---- albums

    @PostMapping("/albums")
    ResponseEntity<AlbumView> createAlbum(@Valid @RequestBody CreateAlbum body) {
        AlbumView view = albums.create(body.title(), body.artistId(), body.releaseDate(), body.type(), body.coverUrl(),
                body.label(), body.genres());
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/albums/" + view.id())).body(view);
    }

    @PatchMapping("/albums/{id}")
    AlbumView updateAlbum(@PathVariable UUID id, @Valid @RequestBody UpdateAlbum body) {
        return albums.update(id, body.title(), body.releaseDate(), body.type(), body.coverUrl(), body.label(),
                body.genres());
    }

    @DeleteMapping("/albums/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteAlbum(@PathVariable UUID id) {
        albums.delete(id);
    }

    // ---- tracks

    @PostMapping("/tracks")
    @Operation(summary = "Create a DRAFT track")
    ResponseEntity<AdminTrackView> createTrack(@Valid @RequestBody CreateTrack body) {
        AdminTrackView view = tracks.create(body.title(), body.albumId(), body.trackNumber(), body.discNumber(),
                body.explicit(), body.isrc(), credits(body.artists()));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/admin/tracks/" + view.id())).body(view);
    }

    @GetMapping("/tracks/{id}")
    @Operation(summary = "Track in any status (processing details included)")
    AdminTrackView track(@PathVariable UUID id) {
        return tracks.get(id);
    }

    @PatchMapping("/tracks/{id}")
    AdminTrackView updateTrack(@PathVariable UUID id, @Valid @RequestBody UpdateTrack body) {
        return tracks.update(id, body.title(), body.trackNumber(), body.discNumber(), body.explicit(), body.isrc(),
                credits(body.artists()));
    }

    @DeleteMapping("/tracks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteTrack(@PathVariable UUID id) {
        tracks.delete(id);
    }

    @GetMapping("/tracks")
    @Operation(summary = "Processing dashboard, most recently changed first (?status=FAILED)")
    CursorPage<AdminTrackView> listTracks(@RequestParam(required = false) TrackStatus status,
                                          @RequestParam(required = false) Integer limit,
                                          @RequestParam(required = false) String cursor) {
        return tracks.list(status, CursorRequest.of(limit, cursor));
    }

    @PostMapping("/tracks/{id}/upload-url")
    @Operation(summary = "Presigned PUT URL for the raw audio (mp3, flac, wav, m4a; at most 200 MB)",
            description = "Upload with exactly the returned headers and sizeBytes bytes. Calling again issues a new URL.")
    UploadUrl uploadUrl(@PathVariable UUID id, @Valid @RequestBody UploadUrlRequest body) {
        return uploads.uploadUrl(id, body.extension(), body.sizeBytes());
    }

    @PostMapping("/tracks/{id}/upload-complete")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Verify the upload and start transcoding (idempotent while PROCESSING)")
    AdminTrackView uploadComplete(@PathVariable UUID id) {
        return uploads.complete(id);
    }

    @PostMapping("/tracks/{id}/retranscode")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Retry transcoding of a FAILED track")
    AdminTrackView retranscode(@PathVariable UUID id) {
        return uploads.retranscode(id);
    }

    private static List<Credit> credits(List<CreditRequest> requests) {
        return requests == null ? null : requests.stream().map(c -> new Credit(c.artistId(), c.role())).toList();
    }
}
