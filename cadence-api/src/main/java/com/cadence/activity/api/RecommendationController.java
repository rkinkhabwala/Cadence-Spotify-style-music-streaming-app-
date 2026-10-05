package com.cadence.activity.api;

import com.cadence.activity.application.ActivityViews.Recommendations;
import com.cadence.activity.application.RecommendationService;
import com.cadence.common.error.BadRequestException;
import com.cadence.common.security.CurrentUser;
import com.cadence.common.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.V1)
@Tag(name = "Activity")
class RecommendationController {

    static final int DEFAULT_LIMIT = 30;
    static final int MAX_LIMIT = 50;

    private final RecommendationService recommendations;

    RecommendationController(RecommendationService recommendations) {
        this.recommendations = recommendations;
    }

    @GetMapping("/me/recommendations")
    @Operation(summary = "Recommended tracks from the recommender (spec 3.5)",
            description = "READY tracks the user hasn't liked, from the recommender (cached 10 min), or popular tracks "
                    + "from the user's top genres when the recommender is unavailable (source=fallback). With "
                    + "seedTrackId: tracks like that one. limit 1-50 (default 30); not paginated.")
    Recommendations recommendations(CurrentUser user, @RequestParam(required = false) Integer limit,
                                    @RequestParam(required = false) UUID seedTrackId) {
        int n = limit == null ? DEFAULT_LIMIT : limit;
        if (n < 1 || n > MAX_LIMIT) {
            throw new BadRequestException("invalid-limit", "limit must be between 1 and " + MAX_LIMIT);
        }
        return recommendations.recommend(user.id(), n, seedTrackId);
    }
}
