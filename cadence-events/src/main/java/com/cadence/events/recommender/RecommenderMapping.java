package com.cadence.events.recommender;

import com.cadence.events.EntityChangedPayload;
import com.cadence.events.EventEnvelope;
import com.cadence.events.EventTypes;
import com.cadence.events.ItemTypes;
import com.cadence.events.TrackPlayedPayload;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reference mapping from Cadence's event envelope to the recommender's public JSON contracts (D88, INTEGRATION.md):
 * {@code POST /v1/events} items ({@link UserEvent}) and {@code POST /v1/catalog/items} items ({@link CatalogItem}).
 * It lives in {@code cadence-events} because that module is shared with the recommender (spec 3.1), so the bridge
 * that consumes Cadence's topics can use it as is.
 *
 * <p>Events without an equivalent in the recommender's model today ({@code track-unliked},
 * {@code artist-unfollowed}) map to empty; INTEGRATION.md asks for UNLIKE/UNFOLLOW event types.
 */
public final class RecommenderMapping {

    /** The only content domain Cadence feeds. */
    public static final String DOMAIN = "song";

    /** The recommender's {@code EventDto} (camelCase JSON). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserEvent(String eventId, String userId, String itemId, String domain, String eventType, Double value,
                            Instant eventTs, String sessionId, Context context, Media media, String recommendationId,
                            Integer position) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Context(String device, String surface) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Media(Long positionMs, Long durationMs) {
    }

    /** The recommender's {@code CatalogItemDto}: {@code artistId}/{@code artistName} are the primary artist. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CatalogItem(String itemId, String domain, String title, String artistId, String artistName,
                              List<String> genres, Long durationMs, LocalDate releaseDate, Boolean explicit) {
    }

    /** A catalog change: upsert {@code item}, or delete {@code itemId} when {@code item} is null. */
    public record CatalogChange(String itemId, CatalogItem item) {

        public boolean isDelete() {
            return item == null;
        }
    }

    private RecommenderMapping() {
    }

    /**
     * Maps a user event. A playback is reported more than once (D68), so only its final report is a PLAY_END or SKIP;
     * the 30-second progress report becomes PLAY_START, which the recommender doesn't weight, so a playback is
     * never counted twice.
     */
    public static Optional<UserEvent> toUserEvent(EventEnvelope event, ObjectMapper mapper) {
        if (event.userId() == null) {
            return Optional.empty();
        }
        String eventId = event.eventId().toString();
        String userId = event.userId().toString();
        String itemId = event.itemId().toString();
        return switch (event.eventType()) {
            case EventTypes.TRACK_PLAYED -> {
                TrackPlayedPayload play = event.payloadAs(TrackPlayedPayload.class, mapper);
                String type = play.skipped() ? "skip" : play.completed() ? "play_end" : "play_start";
                Long duration = play.durationMs() == null ? null : play.durationMs().longValue();
                String session = play.sessionId() != null ? play.sessionId() : "play-" + play.playId();
                yield Optional.of(new UserEvent(eventId, userId, itemId, DOMAIN, type, play.msPlayed() / 1000.0,
                        event.occurredAt(), session,
                        new Context(null, play.recommendationId() != null ? "home" : play.source().toLowerCase(Locale.ROOT)),
                        new Media(play.msPlayed(), duration), play.recommendationId(), play.position()));
            }
            case EventTypes.TRACK_LIKED -> Optional.of(new UserEvent(eventId, userId, itemId, DOMAIN, "like", null,
                    event.occurredAt(), eventId, null, null, null, null));
            // INTEGRATION.md G4: the recommender treats FOLLOW's itemId as an item today; Cadence follows artists
            case EventTypes.ARTIST_FOLLOWED -> Optional.of(new UserEvent(eventId, userId, itemId, DOMAIN, "follow",
                    null, event.occurredAt(), eventId, null, null, null, null));
            default -> Optional.empty();
        };
    }

    /**
     * Maps {@code catalog.entity-changed}. Only songs become recommender items: a READY track is upserted, anything
     * else (deleted, or not playable) is deleted. Artist and album changes are skipped because the catalog re-emits
     * the affected tracks with fresh names and genres.
     */
    public static Optional<CatalogChange> toCatalogChange(EventEnvelope event, ObjectMapper mapper) {
        if (!EventTypes.ENTITY_CHANGED.equals(event.eventType()) || !ItemTypes.SONG.equals(event.itemType())) {
            return Optional.empty();
        }
        String itemId = event.itemId().toString();
        EntityChangedPayload change = event.payloadAs(EntityChangedPayload.class, mapper);
        JsonNode track = change.snapshot();
        if (change.action() == EntityChangedPayload.Action.DELETED || track == null
                || !"READY".equals(track.path("status").asText())) {
            return Optional.of(new CatalogChange(itemId, null));
        }
        JsonNode primary = null;
        for (JsonNode credit : track.path("artists")) {
            if ("PRIMARY".equals(credit.path("role").asText())) {
                primary = credit;
                break;
            }
        }
        List<String> genres = new ArrayList<>();
        track.path("genres").forEach(g -> genres.add(g.asText().toLowerCase(Locale.ROOT)));
        JsonNode release = track.path("releaseDate");
        return Optional.of(new CatalogChange(itemId, new CatalogItem(itemId, DOMAIN, track.path("title").asText(),
                primary == null ? null : primary.path("id").asText(),
                primary == null ? null : primary.path("name").asText(),
                List.copyOf(genres),
                track.hasNonNull("durationMs") ? track.get("durationMs").asLong() : null,
                release.isTextual() ? LocalDate.parse(release.asText()) : null,
                track.path("explicit").asBoolean(false))));
    }
}
