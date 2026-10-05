package com.cadence.activity.api;

import com.cadence.activity.application.ActivityQueries;
import com.cadence.activity.application.ActivityViews.PlayView;
import com.cadence.activity.application.ActivityViews.RecentlyPlayedItem;
import com.cadence.activity.application.ActivityViews.TopTrackItem;
import com.cadence.activity.application.PlayService;
import com.cadence.activity.application.PlayService.ReportPlay;
import com.cadence.activity.domain.PlaySource;
import com.cadence.activity.domain.TopRange;
import com.cadence.common.pagination.CursorPage;
import com.cadence.common.pagination.CursorRequest;
import com.cadence.common.security.CurrentUser;
import com.cadence.common.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.V1)
@Tag(name = "Activity")
class ActivityController {

    /**
     * @param playId identifies one playback; send the same id for its 30-second and its completion/skip report
     */
    record ReportPlayRequest(
            UUID playId,
            @NotNull UUID trackId,
            @NotNull @Min(0) @Max(86_400_000) Integer msPlayed,
            @NotNull PlaySource source,
            UUID sourceId,
            Boolean completed,
            Boolean skipped) {
    }

    private final PlayService plays;
    private final ActivityQueries queries;

    ActivityController(PlayService plays, ActivityQueries queries) {
        this.plays = plays;
        this.queries = queries;
    }

    @PostMapping("/activity/plays")
    @Operation(summary = "Report playback progress (at 30 s and on completion/skip)",
            description = "Idempotent per playId: reports of one playback merge (msPlayed only grows, completed/skipped "
                    + "stick). A play counts toward the track's play count once, when msPlayed first reaches 30 000. "
                    + "Without playId every call is a separate playback.")
    PlayView report(CurrentUser user, @Valid @RequestBody ReportPlayRequest body) {
        return plays.report(user.id(), new ReportPlay(body.playId(), body.trackId(), body.msPlayed(), body.source(),
                body.sourceId(), Boolean.TRUE.equals(body.completed()), Boolean.TRUE.equals(body.skipped())));
    }

    @GetMapping("/me/recently-played")
    @Operation(summary = "The last 50 distinct tracks played, most recent first")
    CursorPage<RecentlyPlayedItem> recentlyPlayed(CurrentUser user, @RequestParam(required = false) Integer limit) {
        return new CursorPage<>(queries.recentlyPlayed(user.id(), limit), null);
    }

    @GetMapping("/me/top/tracks")
    @Operation(summary = "Most-streamed tracks: range short (4 weeks), medium (6 months, default) or long (all time)")
    CursorPage<TopTrackItem> topTracks(CurrentUser user, @RequestParam(required = false) String range,
                                       @RequestParam(required = false) Integer limit,
                                       @RequestParam(required = false) String cursor) {
        return queries.topTracks(user.id(), TopRange.parse(range), CursorRequest.of(limit, cursor));
    }
}
