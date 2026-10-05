package com.cadence.search.infrastructure;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.MsearchResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.msearch.MultiSearchResponseItem;
import co.elastic.clients.elasticsearch.core.msearch.RequestItem;
import co.elastic.clients.json.JsonData;
import com.cadence.search.domain.SearchDocuments.AlbumDoc;
import com.cadence.search.domain.SearchDocuments.ArtistDoc;
import com.cadence.search.domain.SearchType;
import com.cadence.search.domain.SearchUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Elasticsearch access for the search context: index lifecycle (mappings in {@code resources/search/*.json}),
 * document writes and the two query shapes. Connection failures become {@link SearchUnavailableException}.
 */
@Component
public class SearchStore {

    /** One index document plus its relevance score. */
    public record Hit<T>(SearchType type, T source, double score) {
    }

    /** One type's slice of results: the hits on this page and the total number of matches. */
    public record Page(List<JsonNode> sources, long total) {
    }

    private record Fields(String phrase, List<String> prefix, List<String> fuzzy) {
    }

    private static final Map<SearchType, Fields> FIELDS = Map.of(
            SearchType.TRACK, new Fields("title", sayt("title", "artists.name"), List.of("title^3", "artists.name^2", "albumTitle")),
            SearchType.ARTIST, new Fields("name", sayt("name"), List.of("name^3")),
            SearchType.ALBUM, new Fields("title", sayt("title", "artistName"), List.of("title^3", "artistName")),
            SearchType.PLAYLIST, new Fields("name", sayt("name"), List.of("name^3", "description")));

    private final ElasticsearchClient client;
    private final ObjectMapper objectMapper;
    private final String prefix;

    public SearchStore(ElasticsearchClient client, ObjectMapper objectMapper,
                       @Value("${cadence.search.index-prefix}") String prefix) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.prefix = prefix;
    }

    public String indexName(SearchType type) {
        return prefix + "-" + type.indexSuffix();
    }

    // ---- index lifecycle

    /** Creates missing indices. Safe to call concurrently from several instances. @return whether any was created */
    public boolean ensureIndices() {
        boolean created = false;
        for (SearchType type : SearchType.values()) {
            String name = indexName(type);
            try {
                if (client.indices().exists(e -> e.index(name)).value()) {
                    continue;
                }
                try (InputStream json = new ClassPathResource("search/" + type.indexSuffix() + ".json").getInputStream()) {
                    client.indices().create(c -> c.index(name).withJson(json));
                    created = true;
                }
            } catch (ElasticsearchException e) {
                if (!"resource_already_exists_exception".equals(e.error().type())) {
                    throw e;
                }
            } catch (IOException e) {
                throw new SearchUnavailableException(e);
            }
        }
        return created;
    }

    public void dropIndices() {
        List<String> names = Arrays.stream(SearchType.values()).map(this::indexName).toList();
        io(() -> client.indices().delete(d -> d.index(names).ignoreUnavailable(true)));
    }

    // ---- documents

    public void upsert(SearchType type, String id, Object document) {
        JsonNode json = objectMapper.valueToTree(document);
        io(() -> client.index(i -> i.index(indexName(type)).id(id).document(json)));
    }

    public void delete(SearchType type, String id) {
        try {
            io(() -> client.delete(d -> d.index(indexName(type)).id(id)));
        } catch (ElasticsearchException e) {
            if (e.status() != 404) {
                throw e;
            }
        }
    }

    /** Real-time lookup (not subject to the refresh interval). */
    public <T> Optional<T> get(SearchType type, String id, Class<T> documentType) {
        GetResponse<JsonNode> response = io(() -> client.get(g -> g.index(indexName(type)).id(id), JsonNode.class));
        return response.found() ? Optional.of(convert(response.source(), documentType)) : Optional.empty();
    }

    /** Real-time multi-get of artist documents keyed by id; missing ids are absent. */
    public Map<String, ArtistDoc> artists(Collection<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<String> distinct = ids.stream().distinct().toList();
        var response = io(() -> client.mget(m -> m.index(indexName(SearchType.ARTIST)).ids(distinct), JsonNode.class));
        Map<String, ArtistDoc> found = new HashMap<>();
        response.docs().forEach(item -> {
            if (item.isResult() && item.result().found()) {
                found.put(item.result().id(), convert(item.result().source(), ArtistDoc.class));
            }
        });
        return found;
    }

    public Optional<AlbumDoc> album(String id) {
        return get(SearchType.ALBUM, id, AlbumDoc.class);
    }

    /** Writes a renamed artist into the album and track documents that embed its name. */
    public void propagateArtistName(String artistId, String name) {
        refresh(SearchType.ALBUM, SearchType.TRACK); // update_by_query only sees refreshed documents
        Map<String, JsonData> params = Map.of("id", JsonData.of(artistId), "name", JsonData.of(name));
        updateByQuery(SearchType.ALBUM, Query.of(q -> q.term(t -> t.field("artistId").value(artistId))),
                "ctx._source.artistName = params.name", params);
        updateByQuery(SearchType.TRACK, Query.of(q -> q.term(t -> t.field("artists.id").value(artistId))),
                "for (a in ctx._source.artists) { if (a.id == params.id) { a.name = params.name } }", params);
    }

    /** Writes an album's new title and cover into its track documents. */
    public void propagateAlbum(String albumId, String title, String coverUrl) {
        refresh(SearchType.TRACK);
        Map<String, JsonData> params = new HashMap<>();
        params.put("title", JsonData.of(title));
        params.put("cover", JsonData.of(coverUrl == null ? "" : coverUrl));
        updateByQuery(SearchType.TRACK, Query.of(q -> q.term(t -> t.field("albumId").value(albumId))),
                "ctx._source.albumTitle = params.title; ctx._source.albumCoverUrl = params.cover == '' ? null : params.cover",
                params);
    }

    // ---- queries

    /** One multi-search round trip: a page of results per requested type. */
    public Map<SearchType, Page> search(Set<SearchType> types, String text, int from, int size) {
        List<SearchType> ordered = types.stream().sorted().toList();
        List<RequestItem> searches = ordered.stream().map(type -> RequestItem.of(r -> r
                .header(h -> h.index(indexName(type)))
                .body(b -> b.query(query(type, text)).from(from).size(size).trackTotalHits(t -> t.enabled(true)))))
                .toList();
        MsearchResponse<JsonNode> response = io(() -> client.msearch(m -> m.searches(searches), JsonNode.class));
        Map<SearchType, Page> pages = new EnumMap<>(SearchType.class);
        for (int i = 0; i < ordered.size(); i++) {
            MultiSearchResponseItem<JsonNode> item = response.responses().get(i);
            if (item.isFailure()) {
                throw new IllegalStateException("Search of " + ordered.get(i) + " failed: " + item.failure().error().reason());
            }
            var hits = item.result().hits();
            pages.put(ordered.get(i), new Page(hits.hits().stream().map(co.elastic.clients.elasticsearch.core.search.Hit::source).toList(),
                    hits.total() == null ? hits.hits().size() : hits.total().value()));
        }
        return pages;
    }

    /** Search-as-you-type across all indices in one request, best matches first (artists slightly boosted). */
    public List<Hit<JsonNode>> suggest(String text, int size) {
        Map<String, SearchType> typeByIndex = new LinkedHashMap<>();
        for (SearchType type : SearchType.values()) {
            typeByIndex.put(indexName(type), type);
        }
        Query query = Query.of(q -> q.bool(b -> b
                .should(s -> s.multiMatch(m -> m.query(text).type(TextQueryType.BoolPrefix).boost(2f)
                        .fields(sayt("name", "title"))))
                .should(s -> s.multiMatch(m -> m.query(text).fields("name^3", "title^3").fuzziness("AUTO")
                        .prefixLength(1).operator(Operator.And)))
                .should(s -> s.multiMatch(m -> m.query(text).type(TextQueryType.Phrase).fields("name", "title").boost(3f)))
                .minimumShouldMatch("1")));
        SearchResponse<JsonNode> response = io(() -> client.search(s -> s
                .index(new ArrayList<>(typeByIndex.keySet()))
                .ignoreUnavailable(true)
                .indicesBoost(co.elastic.clients.util.NamedValue.of(indexName(SearchType.ARTIST), 1.5))
                .query(query).size(size), JsonNode.class));
        return response.hits().hits().stream()
                .map(h -> new Hit<>(typeByIndex.get(h.index()), h.source(), h.score() == null ? 0 : h.score()))
                .toList();
    }

    private static Query query(SearchType type, String text) {
        Fields fields = FIELDS.get(type);
        return Query.of(q -> q.bool(b -> b
                .should(s -> s.multiMatch(m -> m.query(text).type(TextQueryType.BoolPrefix).fields(fields.prefix()).boost(2f)))
                .should(s -> s.multiMatch(m -> m.query(text).fields(fields.fuzzy()).fuzziness("AUTO").prefixLength(1)
                        .operator(Operator.And)))
                .should(s -> s.matchPhrase(p -> p.field(fields.phrase()).query(text).boost(3f)))
                .minimumShouldMatch("1")));
    }

    /** The {@code search_as_you_type} subfields of each field: the field itself plus its 2- and 3-gram shingles. */
    private static List<String> sayt(String... fields) {
        List<String> result = new ArrayList<>();
        for (String field : fields) {
            result.add(field + ".sayt");
            result.add(field + ".sayt._2gram");
            result.add(field + ".sayt._3gram");
        }
        return result;
    }

    private void refresh(SearchType... types) {
        List<String> names = Arrays.stream(types).map(this::indexName).toList();
        io(() -> client.indices().refresh(r -> r.index(names)));
    }

    private void updateByQuery(SearchType type, Query query, String script, Map<String, JsonData> params) {
        io(() -> client.updateByQuery(u -> u.index(indexName(type)).query(query)
                .script(s -> s.source(script).params(params)).conflicts(Conflicts.Proceed).refresh(true)));
    }

    private <T> T convert(JsonNode source, Class<T> type) {
        try {
            return objectMapper.treeToValue(source, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unexpected " + type.getSimpleName() + " document: " + source, e);
        }
    }

    @FunctionalInterface
    private interface IoCall<T> {
        T call() throws IOException;
    }

    private static <T> T io(IoCall<T> call) {
        try {
            return call.call();
        } catch (IOException e) {
            throw new SearchUnavailableException(e);
        }
    }
}
