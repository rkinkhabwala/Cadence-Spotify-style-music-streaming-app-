package com.cadence.library.api;

import com.cadence.library.domain.Visibility;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

final class LibraryDtos {

    private LibraryDtos() {
    }

    record CreatePlaylist(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 300) String description,
            Visibility visibility,
            Boolean collaborative) {
    }

    record UpdatePlaylist(
            @Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
            @Size(max = 300) String description,
            Visibility visibility,
            Boolean collaborative) {
    }

    record AddTracks(
            @NotEmpty @Size(max = 100) List<@NotNull UUID> trackIds,
            @Min(0) Integer position) {
    }

    record RemoveTracks(@NotEmpty @Size(max = 100) List<@NotNull UUID> trackIds) {
    }

    record Reorder(@NotNull UUID trackId, UUID afterTrackId) {
    }
}
