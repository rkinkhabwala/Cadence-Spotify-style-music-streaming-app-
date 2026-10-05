package com.cadence.events.recommender;

import com.cadence.events.CadenceJackson;
import com.cadence.events.EntityChangedPayload;
import com.cadence.events.EntityChangedPayload.Action;
import com.cadence.events.EventEnvelope;
import com.cadence.events.EventTypes;
import com.cadence.events.ItemTypes;
import com.cadence.events.TrackPlayedPayload;
import com.cadence.events.UuidV7;
import com.cadence.events.recommender.RecommenderMapping.CatalogChange;
import com.cadence.events.recommender.RecommenderMapping.UserEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class RecommenderMappingTest {

    private static final ObjectMapper MAPPER = CadenceJackson.newObjectMapper();
    private static final Instant AT = Instant.parse("2026-10-05T15:42:00Z");
    private final UUID user = UuidV7.generate();
    private final UUID track = UuidV7.generate();

    @Test
    void aPlaybackBecomesPlayStartThenPlayEndOrSkipSoItIsWeightedOnce() {
        UUID playId = UuidV7.generate();
        UserEvent progress = map(played(new TrackPlayedPayload(playId, 30_000, false, false, "PLAYLIST", null, 200_000,
                "tab-1", null, null)));
        UserEvent completed = map(played(new TrackPlayedPayload(playId, 199_000, true, false, "PLAYLIST", null, 200_000,
                "tab-1", null, null)));
        UserEvent skipped = map(played(new TrackPlayedPayload(UuidV7.generate(), 7_200, false, true, "SEARCH", null,
                214_000, null, "01929a7d-rec", 3)));

        assertThat(progress.eventType()).isEqualTo("play_start");
        assertThat(completed.eventType()).isEqualTo("play_end");
        assertThat(completed.value()).isEqualTo(199.0);
        assertThat(completed.media().durationMs()).isEqualTo(200_000);
        assertThat(completed.sessionId()).isEqualTo("tab-1");
        assertThat(completed.context().surface()).isEqualTo("playlist");
        assertThat(skipped.eventType()).isEqualTo("skip");
        assertThat(skipped.media().positionMs()).isEqualTo(7_200);
        assertThat(skipped.recommendationId()).isEqualTo("01929a7d-rec");
        assertThat(skipped.position()).isEqualTo(3);
        assertThat(skipped.context().surface()).as("played from a recommended shelf").isEqualTo("home");
        assertThat(skipped.sessionId()).as("falls back to the playback").startsWith("play-");
        for (UserEvent e : List.of(progress, completed, skipped)) {
            assertAcceptedByRecommender(e);
            assertThat(e.userId()).isEqualTo(user.toString());
            assertThat(e.itemId()).isEqualTo(track.toString());
            assertThat(e.eventTs()).isEqualTo(AT);
        }
    }

    @Test
    void likesAndFollowsMapAndRetractionsHaveNoEquivalentYet() {
        UUID artist = UuidV7.generate();
        UserEvent like = map(EventEnvelope.create(EventTypes.TRACK_LIKED, AT, user, ItemTypes.SONG, track, null, MAPPER));
        UserEvent follow = map(EventEnvelope.create(EventTypes.ARTIST_FOLLOWED, AT, user, ItemTypes.ARTIST, artist, null,
                MAPPER));

        assertThat(like.eventType()).isEqualTo("like");
        assertThat(follow.eventType()).isEqualTo("follow");
        assertThat(follow.itemId()).isEqualTo(artist.toString());
        assertAcceptedByRecommender(like);
        assertAcceptedByRecommender(follow);
        for (String retraction : List.of(EventTypes.TRACK_UNLIKED, EventTypes.ARTIST_UNFOLLOWED)) {
            assertThat(RecommenderMapping.toUserEvent(EventEnvelope.create(retraction, AT, user, ItemTypes.SONG, track,
                    null, MAPPER), MAPPER)).as(retraction).isEmpty();
        }
    }

    @Test
    void readyTracksBecomeCatalogItemsAndEverythingElseADelete() {
        UUID primary = UuidV7.generate();
        Map<String, Object> snapshot = Map.of("id", track, "title", "Night Ferries", "durationMs", 214_000,
                "explicit", true, "status", "READY", "genres", List.of("Lo-fi", "Jazz"), "releaseDate", "2020-10-09",
                "artists", List.of(Map.of("id", UuidV7.generate(), "name", "Guest", "role", "FEATURED"),
                        Map.of("id", primary, "name", "Velvet Static", "role", "PRIMARY")));

        CatalogChange upsert = catalog(Action.UPDATED, snapshot);
        CatalogChange notReady = catalog(Action.UPDATED, Map.of("id", track, "title", "x", "status", "PROCESSING"));
        CatalogChange deleted = catalog(Action.DELETED, null);

        assertThat(upsert.isDelete()).isFalse();
        assertThat(upsert.item()).isEqualTo(new RecommenderMapping.CatalogItem(track.toString(), "song", "Night Ferries",
                primary.toString(), "Velvet Static", List.of("lo-fi", "jazz"), 214_000L, LocalDate.of(2020, 10, 9), true));
        assertThat(notReady.isDelete()).isTrue();
        assertThat(deleted.isDelete()).isTrue();
        assertThat(RecommenderMapping.toCatalogChange(EventEnvelope.create(EventTypes.ENTITY_CHANGED, AT, null,
                ItemTypes.ALBUM, UuidV7.generate(), new EntityChangedPayload(Action.UPDATED, null), MAPPER), MAPPER))
                .as("albums are not recommender items").isEmpty();
    }

    @Test
    void serializesToTheRecommendersCamelCaseJson() {
        JsonNode json = MAPPER.valueToTree(map(played(new TrackPlayedPayload(UuidV7.generate(), 40_000, true, false,
                "ALBUM", null, 41_000, "tab", null, null))));

        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("eventId", "userId", "itemId", "domain",
                "eventType", "value", "eventTs", "sessionId", "context", "media");
        assertThat(json.get("eventTs").asText()).isEqualTo("2026-10-05T15:42:00Z");
    }

    // ------------------------------------------------------------------------------------------------------------

    private EventEnvelope played(TrackPlayedPayload payload) {
        return EventEnvelope.create(EventTypes.TRACK_PLAYED, AT, user, ItemTypes.SONG, track, payload, MAPPER);
    }

    private static UserEvent map(EventEnvelope event) {
        return RecommenderMapping.toUserEvent(event, MAPPER).orElseThrow();
    }

    private CatalogChange catalog(Action action, Map<String, Object> snapshot) {
        return RecommenderMapping.toCatalogChange(EventEnvelope.create(EventTypes.ENTITY_CHANGED, AT, null, ItemTypes.SONG,
                track, new EntityChangedPayload(action, snapshot == null ? null : MAPPER.valueToTree(snapshot)), MAPPER),
                MAPPER).orElseThrow();
    }

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");
    private static final Set<String> TYPES = Set.of("impression", "click", "play_start", "play_end", "skip", "replay",
            "seek", "like", "dislike", "share", "save", "comment", "rate", "follow", "not_interested", "dwell", "search",
            "search_result_click");

    /** The recommender's ingestion rules (services/ingestion-api EventValidator, read 2026-10-05). */
    static void assertAcceptedByRecommender(UserEvent e) {
        assertThat(UUID.fromString(e.eventId())).isNotNull();
        assertThat(e.userId()).matches(ID);
        assertThat(e.itemId()).matches(ID);
        assertThat(e.domain()).isEqualTo("song");
        assertThat(TYPES).contains(e.eventType());
        assertThat(e.sessionId()).isNotBlank().hasSizeLessThanOrEqualTo(128);
        assertThat(e.eventTs()).isNotNull();
        if (e.value() != null) {
            assertThat(e.value()).isNotNegative();
        }
        if (e.recommendationId() != null) {
            assertThat(e.recommendationId()).hasSizeLessThanOrEqualTo(64);
        }
        if (e.position() != null) {
            assertThat(e.position()).isNotNegative();
        }
    }
}
