package com.cadence.identity.api;

import com.cadence.common.security.CurrentUser;
import com.cadence.common.web.ApiPaths;
import com.cadence.identity.api.AuthDtos.UpdateProfileRequest;
import com.cadence.identity.application.ProfileService;
import com.cadence.identity.application.UserProfile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.V1 + "/me")
@Tag(name = "Profile")
class MeController {

    private final ProfileService profiles;

    MeController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    @Operation(summary = "Current user's profile")
    UserProfile me(CurrentUser user) {
        return profiles.me(user.id());
    }

    @PatchMapping
    @Operation(summary = "Update display name, avatar URL or country (omitted fields unchanged; idempotent)")
    UserProfile update(CurrentUser user, @Valid @RequestBody UpdateProfileRequest request) {
        return profiles.update(user.id(), request.displayName(), request.avatarUrl(), request.country());
    }
}
