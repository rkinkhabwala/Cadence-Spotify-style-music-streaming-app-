package com.cadence.catalog.api;

import com.cadence.catalog.domain.AlbumType;
import com.cadence.catalog.domain.ArtistRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Admin request bodies. In PATCH bodies omitted ({@code null}) fields are unchanged and an empty string clears an
 * optional text field.
 */
final class CatalogDtos {

    static final String NOT_BLANK = ".*\\S.*";
    static final String ISRC = "^$|[A-Z]{2}[A-Z0-9]{3}[0-9]{7}";
    static final String HTTP_URL = "^(https?://.*)?$";

    private CatalogDtos() {
    }

    record CreateArtist(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 5000) String bio,
            @Size(max = 2048) @URL(regexp = HTTP_URL) String imageUrl,
            Boolean verified) {
    }

    record UpdateArtist(
            @Size(min = 1, max = 200) @Pattern(regexp = NOT_BLANK) String name,
            @Size(max = 5000) String bio,
            @Size(max = 2048) @URL(regexp = HTTP_URL) String imageUrl,
            Boolean verified) {
    }

    record CreateAlbum(
            @NotBlank @Size(max = 300) String title,
            @NotNull UUID artistId,
            @NotNull LocalDate releaseDate,
            @NotNull AlbumType type,
            @Size(max = 2048) @URL(regexp = HTTP_URL) String coverUrl,
            @Size(max = 200) String label,
            @Size(max = 10) List<@NotBlank @Size(max = 100) String> genres) {
    }

    record UpdateAlbum(
            @Size(min = 1, max = 300) @Pattern(regexp = NOT_BLANK) String title,
            LocalDate releaseDate,
            AlbumType type,
            @Size(max = 2048) @URL(regexp = HTTP_URL) String coverUrl,
            @Size(max = 200) String label,
            @Size(max = 10) List<@NotBlank @Size(max = 100) String> genres) {
    }

    record CreditRequest(@NotNull UUID artistId, @NotNull ArtistRole role) {
    }

    record CreateTrack(
            @NotBlank @Size(max = 300) String title,
            @NotNull UUID albumId,
            @NotNull @Min(1) Integer trackNumber,
            @Min(1) Integer discNumber,
            Boolean explicit,
            @Pattern(regexp = ISRC, message = "must be a 12-character ISRC") String isrc,
            @Size(max = 10) List<@Valid @NotNull CreditRequest> artists) {
    }

    record UpdateTrack(
            @Size(min = 1, max = 300) @Pattern(regexp = NOT_BLANK) String title,
            @Min(1) Integer trackNumber,
            @Min(1) Integer discNumber,
            Boolean explicit,
            @Pattern(regexp = ISRC, message = "must be a 12-character ISRC") String isrc,
            @Size(min = 1, max = 10) List<@Valid @NotNull CreditRequest> artists) {
    }

    record UploadUrlRequest(
            @NotBlank @Size(max = 10) String extension,
            @NotNull @Positive Long sizeBytes) {
    }
}
