package com.cadence.search.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Index documents and their construction from event snapshots. Snapshots are the producers' public read models
 * ({@code ArtistView}, {@code AlbumView}, {@code TrackSummary}, {@code PlaylistView}) as JSON. Denormalized names
 * (artist and album names on tracks and albums) are taken from the current index documents when available, so
 * documents converge to the latest names whatever order events from different entities arrive in.
 */
public final class SearchDocuments {

    private SearchDocuments() {
    }

    public record ArtistDoc(String id, String name, String imageUrl, boolean verified) {
    }

    public record AlbumDoc(String id, String title, String type, String releaseDate, String coverUrl, String artistId,
                           String artistName) {
    }

    public record TrackArtist(String id, String name) {
    }

    public record TrackDoc(String id, String title, Integer durationMs, boolean explicit, String albumId,
                           String albumTitle, String albumCoverUrl, List<TrackArtist> artists) {
    }

    public record PlaylistDoc(String id, String name, String description, String coverUrl, String ownerId,
                              int trackCount) {
    }

    public static ArtistDoc artist(JsonNode snapshot) {
        return new ArtistDoc(text(snapshot, "id"), text(snapshot, "name"), text(snapshot, "imageUrl"),
                snapshot.path("verified").asBoolean(false));
    }

    /** @param currentArtists artist documents already in the index, keyed by id (may be empty) */
    public static AlbumDoc album(JsonNode snapshot, Map<String, ArtistDoc> currentArtists) {
        String artistId = text(snapshot.path("artist"), "id");
        ArtistDoc current = currentArtists.get(artistId);
        String artistName = current != null ? current.name() : text(snapshot.path("artist"), "name");
        return new AlbumDoc(text(snapshot, "id"), text(snapshot, "title"), text(snapshot, "type"),
                text(snapshot, "releaseDate"), text(snapshot, "coverUrl"), artistId, artistName);
    }

    /**
     * Only READY tracks are searchable (spec 4).
     *
     * @return empty if the track is not READY, meaning its document must be removed
     */
    public static Optional<TrackDoc> track(JsonNode snapshot, Map<String, ArtistDoc> currentArtists,
                                           Optional<AlbumDoc> currentAlbum) {
        if (!"READY".equals(text(snapshot, "status"))) {
            return Optional.empty();
        }
        List<TrackArtist> artists = new ArrayList<>();
        for (JsonNode credit : snapshot.path("artists")) {
            String id = text(credit, "id");
            ArtistDoc current = currentArtists.get(id);
            artists.add(new TrackArtist(id, current != null ? current.name() : text(credit, "name")));
        }
        JsonNode album = snapshot.path("album");
        String albumTitle = currentAlbum.map(AlbumDoc::title).orElse(text(album, "title"));
        String coverUrl = currentAlbum.isPresent() ? currentAlbum.get().coverUrl() : text(album, "coverUrl");
        Integer durationMs = snapshot.hasNonNull("durationMs") ? snapshot.get("durationMs").asInt() : null;
        return Optional.of(new TrackDoc(text(snapshot, "id"), text(snapshot, "title"), durationMs,
                snapshot.path("explicit").asBoolean(false), text(album, "id"), albumTitle, coverUrl, artists));
    }

    /** @return empty unless the playlist is PUBLIC (private playlists are never searchable) */
    public static Optional<PlaylistDoc> playlist(JsonNode snapshot) {
        if (!"PUBLIC".equals(text(snapshot, "visibility"))) {
            return Optional.empty();
        }
        return Optional.of(new PlaylistDoc(text(snapshot, "id"), text(snapshot, "name"), text(snapshot, "description"),
                text(snapshot, "coverUrl"), text(snapshot, "ownerId"), snapshot.path("trackCount").asInt(0)));
    }

    /** Artist ids credited in a track snapshot. */
    public static List<String> creditedArtistIds(JsonNode trackSnapshot) {
        List<String> ids = new ArrayList<>();
        trackSnapshot.path("artists").forEach(c -> ids.add(text(c, "id")));
        return ids;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
