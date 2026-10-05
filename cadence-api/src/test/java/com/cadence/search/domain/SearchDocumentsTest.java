package com.cadence.search.domain;

import com.cadence.search.domain.SearchDocuments.AlbumDoc;
import com.cadence.search.domain.SearchDocuments.ArtistDoc;
import com.cadence.search.domain.SearchDocuments.TrackArtist;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SearchDocumentsTest {

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode track(String status) throws Exception {
        return json.readTree("""
                {"id":"t1","title":"Song","durationMs":1000,"explicit":true,"status":"%s","playCount":3,
                 "album":{"id":"al1","title":"Old Album","coverUrl":"http://c/old.png"},
                 "artists":[{"id":"a1","name":"Old Name","role":"PRIMARY"},{"id":"a2","name":"Guest","role":"FEATURED"}]}
                """.formatted(status));
    }

    @Test
    void readyTrackSnapshotBecomesADocumentWithCurrentDenormalizedNames() throws Exception {
        var doc = SearchDocuments.track(track("READY"),
                Map.of("a1", new ArtistDoc("a1", "New Name", null, false)),
                Optional.of(new AlbumDoc("al1", "New Album", "ALBUM", "2020-01-01", null, "a1", "New Name")));

        assertThat(doc).hasValueSatisfying(d -> {
            assertThat(d.title()).isEqualTo("Song");
            assertThat(d.durationMs()).isEqualTo(1000);
            assertThat(d.explicit()).isTrue();
            assertThat(d.albumTitle()).isEqualTo("New Album");
            assertThat(d.albumCoverUrl()).isNull();
            assertThat(d.artists()).containsExactly(new TrackArtist("a1", "New Name"), new TrackArtist("a2", "Guest"));
        });
        assertThat(SearchDocuments.creditedArtistIds(track("READY"))).containsExactly("a1", "a2");
    }

    @Test
    void snapshotNamesAreUsedWhenTheIndexHasNoCurrentDocument() throws Exception {
        var doc = SearchDocuments.track(track("READY"), Map.of(), Optional.empty()).orElseThrow();
        assertThat(doc.albumTitle()).isEqualTo("Old Album");
        assertThat(doc.albumCoverUrl()).isEqualTo("http://c/old.png");
        assertThat(doc.artists().getFirst().name()).isEqualTo("Old Name");
    }

    @Test
    void tracksThatAreNotReadyAreNotIndexed() throws Exception {
        for (String status : new String[]{"DRAFT", "PROCESSING", "FAILED"}) {
            assertThat(SearchDocuments.track(track(status), Map.of(), Optional.empty())).as(status).isEmpty();
        }
    }

    @Test
    void albumTakesTheCurrentArtistName() throws Exception {
        JsonNode album = json.readTree("""
                {"id":"al1","title":"T","type":"EP","releaseDate":"2021-05-06","coverUrl":null,"artist":{"id":"a1","name":"Old"}}
                """);
        assertThat(SearchDocuments.album(album, Map.of()).artistName()).isEqualTo("Old");
        assertThat(SearchDocuments.album(album, Map.of("a1", new ArtistDoc("a1", "New", null, true))))
                .isEqualTo(new AlbumDoc("al1", "T", "EP", "2021-05-06", null, "a1", "New"));
    }

    @Test
    void onlyPublicPlaylistsAreIndexed() throws Exception {
        JsonNode pub = json.readTree("""
                {"id":"p1","ownerId":"u1","name":"Mix","description":"d","coverUrl":null,"visibility":"PUBLIC","trackCount":4}""");
        JsonNode priv = json.readTree("""
                {"id":"p2","ownerId":"u1","name":"Mine","visibility":"PRIVATE","trackCount":0}""");
        assertThat(SearchDocuments.playlist(pub)).hasValue(new SearchDocuments.PlaylistDoc("p1", "Mix", "d", null, "u1", 4));
        assertThat(SearchDocuments.playlist(priv)).isEmpty();
    }
}
