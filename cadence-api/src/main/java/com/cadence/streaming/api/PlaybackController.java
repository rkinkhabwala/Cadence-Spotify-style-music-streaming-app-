package com.cadence.streaming.api;

import com.cadence.common.security.CurrentUser;
import com.cadence.common.web.ApiPaths;
import com.cadence.streaming.application.PlaybackService;
import com.cadence.streaming.application.PlaybackService.PlaybackStart;
import com.cadence.streaming.application.PlaybackService.SkipResult;
import com.cadence.streaming.application.StreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.V1)
@Tag(name = "Streaming")
class PlaybackController {

    static final MediaType M3U8 = MediaType.parseMediaType("application/vnd.apple.mpegurl");

    private final PlaybackService playback;
    private final StreamService streams;

    PlaybackController(PlaybackService playback, StreamService streams) {
        this.playback = playback;
        this.streams = streams;
    }

    @PostMapping("/playback/{trackId}")
    @Operation(summary = "Start playback of a READY track",
            description = "Returns a manifest URL valid for 5 minutes. Free users get at most 160 kbps renditions, and "
                    + "every third start carries an adSlot placeholder to play first. Safe to repeat (each call issues "
                    + "a fresh URL).")
    PlaybackStart start(CurrentUser user, @PathVariable UUID trackId) {
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return playback.start(user.id(), trackId, baseUrl);
    }

    /** @param playId the playback being skipped; makes a retried request count once */
    record SkipRequest(UUID playId) {
    }

    @PostMapping("/playback/{trackId}/skip")
    @Operation(summary = "Ask to skip the playing track (free plan: 6 skips per rolling hour)",
            description = "Call before a user-initiated skip. 429 skip-limit-reached with Retry-After when the free "
                    + "plan's skips are used up. Idempotent per playId; without playId every call counts. Premium: "
                    + "always allowed, remaining = null.")
    SkipResult skip(CurrentUser user, @PathVariable UUID trackId, @RequestBody(required = false) SkipRequest body) {
        return playback.skip(user.id(), trackId, body == null ? null : body.playId());
    }

    @GetMapping("/playback/{trackId}/master.m3u8")
    @Operation(summary = "HLS master playlist (authorized by the token in the URL)")
    ResponseEntity<String> master(@PathVariable UUID trackId, @RequestParam String token) {
        return playlist(playback.masterPlaylist(trackId, token));
    }

    @GetMapping("/playback/{trackId}/{variant}/index.m3u8")
    @Operation(summary = "HLS media playlist of one rendition, with presigned segment URLs")
    ResponseEntity<String> variant(@PathVariable UUID trackId, @PathVariable String variant, @RequestParam String token) {
        return playlist(playback.variantPlaylist(trackId, variant, token));
    }

    @GetMapping("/tracks/{id}/stream")
    @Operation(summary = "Range-request fallback: the 160 kbps AAC file (206 Partial Content, 416 for bad ranges)")
    void stream(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
                HttpServletResponse response) throws IOException {
        StreamService.Slice slice = streams.open(id, range);
        response.setStatus(slice.range().isPartial() ? HttpStatus.PARTIAL_CONTENT.value() : HttpStatus.OK.value());
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setContentType("audio/mp4");
        response.setContentLengthLong(slice.range().length());
        if (slice.range().isPartial()) {
            response.setHeader(HttpHeaders.CONTENT_RANGE, slice.range().contentRange());
        }
        try (InputStream in = streams.read(slice)) {
            in.transferTo(response.getOutputStream());
        }
    }

    private static ResponseEntity<String> playlist(String body) {
        // playlists contain signed URLs: never cache
        return ResponseEntity.ok().contentType(M3U8).cacheControl(CacheControl.noStore()).body(body);
    }
}
