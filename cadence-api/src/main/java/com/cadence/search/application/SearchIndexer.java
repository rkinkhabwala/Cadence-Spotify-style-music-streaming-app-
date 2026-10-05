package com.cadence.search.application;

import com.cadence.events.EntityChangedPayload;
import com.cadence.events.EntityChangedPayload.Action;
import com.cadence.events.EventEnvelope;
import com.cadence.events.ItemTypes;
import com.cadence.search.domain.SearchDocuments;
import com.cadence.search.domain.SearchDocuments.AlbumDoc;
import com.cadence.search.domain.SearchDocuments.ArtistDoc;
import com.cadence.search.domain.SearchType;
import com.cadence.search.infrastructure.SearchStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * Applies entity-changed events to the indices. Every write is a full-document upsert or a delete keyed by the entity
 * id, so applying an event twice (at-least-once delivery) has the same result as applying it once; events of one
 * entity arrive in order because they share a partition key. Elasticsearch can't join a Postgres transaction, so
 * this consumer is idempotent by construction instead of through {@code processed_event}.
 */
@Service
public class SearchIndexer {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexer.class);

    private final SearchStore store;
    private final SearchIndexAdmin admin;
    private final ObjectMapper objectMapper;

    SearchIndexer(SearchStore store, SearchIndexAdmin admin, ObjectMapper objectMapper) {
        this.store = store;
        this.admin = admin;
        this.objectMapper = objectMapper;
    }

    public void apply(EventEnvelope event) {
        admin.ensureIndices();
        EntityChangedPayload change = event.payloadAs(EntityChangedPayload.class, objectMapper);
        String id = event.itemId().toString();
        boolean deleted = change.action() == Action.DELETED || change.snapshot() == null || change.snapshot().isNull();
        switch (event.itemType()) {
            case ItemTypes.ARTIST -> {
                if (deleted) {
                    store.delete(SearchType.ARTIST, id);
                } else {
                    indexArtist(id, change.snapshot());
                }
            }
            case ItemTypes.ALBUM -> {
                if (deleted) {
                    store.delete(SearchType.ALBUM, id);
                } else {
                    indexAlbum(id, change.snapshot());
                }
            }
            case ItemTypes.SONG -> {
                if (deleted) {
                    store.delete(SearchType.TRACK, id);
                } else {
                    indexTrack(id, change.snapshot());
                }
            }
            case ItemTypes.PLAYLIST -> {
                Optional<SearchDocuments.PlaylistDoc> doc = deleted ? Optional.empty() : SearchDocuments.playlist(change.snapshot());
                doc.ifPresentOrElse(d -> store.upsert(SearchType.PLAYLIST, id, d), () -> store.delete(SearchType.PLAYLIST, id));
            }
            default -> log.debug("Ignoring entity-changed for item type {}", event.itemType());
        }
    }

    private void indexArtist(String id, JsonNode snapshot) {
        ArtistDoc doc = SearchDocuments.artist(snapshot);
        Optional<ArtistDoc> previous = store.get(SearchType.ARTIST, id, ArtistDoc.class);
        store.upsert(SearchType.ARTIST, id, doc);
        if (previous.isPresent() && !Objects.equals(previous.get().name(), doc.name())) {
            store.propagateArtistName(id, doc.name());
        }
    }

    private void indexAlbum(String id, JsonNode snapshot) {
        String artistId = snapshot.path("artist").path("id").asText(null);
        AlbumDoc doc = SearchDocuments.album(snapshot, artistId == null ? java.util.Map.of() : store.artists(java.util.List.of(artistId)));
        Optional<AlbumDoc> previous = store.album(id);
        store.upsert(SearchType.ALBUM, id, doc);
        if (previous.isPresent() && (!Objects.equals(previous.get().title(), doc.title())
                || !Objects.equals(previous.get().coverUrl(), doc.coverUrl()))) {
            store.propagateAlbum(id, doc.title(), doc.coverUrl());
        }
    }

    private void indexTrack(String id, JsonNode snapshot) {
        String albumId = snapshot.path("album").path("id").asText(null);
        Optional<SearchDocuments.TrackDoc> doc = SearchDocuments.track(snapshot,
                store.artists(SearchDocuments.creditedArtistIds(snapshot)),
                albumId == null ? Optional.empty() : store.album(albumId));
        doc.ifPresentOrElse(d -> store.upsert(SearchType.TRACK, id, d), () -> store.delete(SearchType.TRACK, id));
    }
}
