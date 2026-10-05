package com.cadence.search.application;

import com.cadence.catalog.CatalogQueries;
import com.cadence.catalog.TrackSummary;
import com.cadence.common.error.BadRequestException;
import com.cadence.common.pagination.Cursor;
import com.cadence.common.pagination.CursorPage;
import com.cadence.common.pagination.CursorRequest;
import com.cadence.common.ratelimit.RateLimiter;
import com.cadence.identity.UserAccounts;
import com.cadence.search.application.SearchViews.AlbumHit;
import com.cadence.search.application.SearchViews.ArtistHit;
import com.cadence.search.application.SearchViews.OwnerRef;
import com.cadence.search.application.SearchViews.PlaylistHit;
import com.cadence.search.application.SearchViews.SearchResults;
import com.cadence.search.application.SearchViews.Suggestion;
import com.cadence.search.application.SearchViews.Suggestions;
import com.cadence.search.domain.SearchDocuments.AlbumDoc;
import com.cadence.search.domain.SearchDocuments.ArtistDoc;
import com.cadence.search.domain.SearchDocuments.PlaylistDoc;
import com.cadence.search.domain.SearchDocuments.TrackArtist;
import com.cadence.search.domain.SearchDocuments.TrackDoc;
import com.cadence.search.domain.SearchType;
import com.cadence.search.infrastructure.SearchStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Search and search-as-you-type (spec 5). Both share the per-user rate limit {@code search} (spec 6: 30/s).
 * Track hits are hydrated from the catalog, so they carry live play counts and stale index entries for tracks that
 * stopped being READY are dropped; other types are served straight from the index.
 */
@Service
public class SearchService {

    static final int MAX_QUERY_LENGTH = 100;
    static final int SUGGESTIONS = 8;
    /** Deep paging stops here (offset + limit); search is for finding, not for listing the catalog. */
    static final int MAX_WINDOW = 1000;

    private final SearchStore store;
    private final SearchIndexAdmin admin;
    private final CatalogQueries catalog;
    private final UserAccounts accounts;
    private final SearchMapper mapper;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    SearchService(SearchStore store, SearchIndexAdmin admin, CatalogQueries catalog, UserAccounts accounts,
                  SearchMapper mapper, RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.store = store;
        this.admin = admin;
        this.catalog = catalog;
        this.accounts = accounts;
        this.mapper = mapper;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    /**
     * @param page {@code limit} per type; a {@code cursor} pages a single type and is rejected for several types
     */
    public SearchResults search(UUID userId, String q, String types, CursorRequest page) {
        rateLimiter.consume("search", userId.toString());
        String text = normalize(q);
        Set<SearchType> requested = SearchType.parse(types);
        if (!page.isFirstPage() && requested.size() != 1) {
            throw new BadRequestException("cursor-needs-single-type", "Pass exactly one type in 'types' when paging with a cursor");
        }
        int from = page.isFirstPage() ? 0 : (int) Math.clamp(page.cursor().longValue(0), 0, MAX_WINDOW);
        int size = Math.max(0, Math.min(page.limit(), MAX_WINDOW - from));
        admin.ensureIndices();
        Map<SearchType, SearchStore.Page> pages = store.search(requested, text, from, size);

        return new SearchResults(text,
                group(pages.get(SearchType.TRACK), from, size, this::tracks),
                group(pages.get(SearchType.ARTIST), from, size, docs -> convert(docs, ArtistDoc.class).stream().map(mapper::toHit).toList()),
                group(pages.get(SearchType.ALBUM), from, size, docs -> convert(docs, AlbumDoc.class).stream().map(mapper::toHit).toList()),
                group(pages.get(SearchType.PLAYLIST), from, size, this::playlists));
    }

    public Suggestions suggest(UUID userId, String q) {
        rateLimiter.consume("search", userId.toString());
        String text = normalize(q);
        admin.ensureIndices();
        List<Suggestion> items = store.suggest(text, SUGGESTIONS).stream().map(hit -> toSuggestion(hit.type(), hit.source())).toList();
        return new Suggestions(text, items);
    }

    private Suggestion toSuggestion(SearchType type, JsonNode source) {
        return switch (type) {
            case TRACK -> {
                TrackDoc t = convert(source, TrackDoc.class);
                yield new Suggestion(type.paramName(), UUID.fromString(t.id()), t.title(),
                        t.artists().stream().map(TrackArtist::name).filter(Objects::nonNull).collect(Collectors.joining(", ")),
                        t.albumCoverUrl());
            }
            case ARTIST -> {
                ArtistDoc a = convert(source, ArtistDoc.class);
                yield new Suggestion(type.paramName(), UUID.fromString(a.id()), a.name(), null, a.imageUrl());
            }
            case ALBUM -> {
                AlbumDoc a = convert(source, AlbumDoc.class);
                yield new Suggestion(type.paramName(), UUID.fromString(a.id()), a.title(), a.artistName(), a.coverUrl());
            }
            case PLAYLIST -> {
                PlaylistDoc p = convert(source, PlaylistDoc.class);
                yield new Suggestion(type.paramName(), UUID.fromString(p.id()), p.name(), null, p.coverUrl());
            }
        };
    }

    private List<TrackSummary> tracks(List<JsonNode> docs) {
        List<UUID> ids = docs.stream().map(d -> UUID.fromString(d.get("id").asText())).toList();
        Map<UUID, TrackSummary> found = catalog.findTracks(ids);
        return ids.stream().map(found::get).filter(t -> t != null && t.isPlayable()).toList();
    }

    private List<PlaylistHit> playlists(List<JsonNode> docs) {
        List<PlaylistDoc> playlists = convert(docs, PlaylistDoc.class);
        Map<UUID, String> owners = accounts.displayNames(playlists.stream().map(p -> UUID.fromString(p.ownerId())).collect(Collectors.toSet()));
        return playlists.stream().map(p -> {
            UUID owner = UUID.fromString(p.ownerId());
            return mapper.toHit(p, new OwnerRef(owner, owners.get(owner)));
        }).toList();
    }

    private static <T> CursorPage<T> group(SearchStore.Page page, int from, int size,
                                           java.util.function.Function<List<JsonNode>, List<T>> convert) {
        if (page == null) {
            return null;
        }
        int next = from + size;
        boolean more = page.total() > next && next < MAX_WINDOW;
        return new CursorPage<>(convert.apply(page.sources()), more ? Cursor.of(next).encode() : null);
    }

    private static String normalize(String q) {
        String text = q == null ? "" : q.strip();
        if (text.isEmpty()) {
            throw new BadRequestException("invalid-query", "Parameter 'q' must not be blank");
        }
        if (text.length() > MAX_QUERY_LENGTH) {
            throw new BadRequestException("invalid-query", "Parameter 'q' must be at most " + MAX_QUERY_LENGTH + " characters");
        }
        return text;
    }

    private <T> List<T> convert(List<JsonNode> docs, Class<T> type) {
        return docs.stream().map(d -> convert(d, type)).toList();
    }

    private <T> T convert(JsonNode doc, Class<T> type) {
        try {
            return objectMapper.treeToValue(doc, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unexpected " + type.getSimpleName() + " document", e);
        }
    }
}
