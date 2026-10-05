package com.cadence.activity.api;

import com.cadence.activity.application.ActivityViews.Home;
import com.cadence.activity.application.HomeService;
import com.cadence.common.security.CurrentUser;
import com.cadence.common.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.V1)
@Tag(name = "Activity")
class HomeController {

    private final HomeService home;

    HomeController(HomeService home) {
        this.home = home;
    }

    @GetMapping("/home")
    @Operation(summary = "Home shelves: recently played, your top tracks, popular right now, new releases")
    Home home(CurrentUser user) {
        return home.home(user.id());
    }
}
