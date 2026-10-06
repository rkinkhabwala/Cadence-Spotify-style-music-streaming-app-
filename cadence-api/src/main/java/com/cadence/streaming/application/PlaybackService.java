package com.cadence.streaming.application;

import com.cadence.catalog.CatalogQueries;
import com.cadence.catalog.TrackSummary;
import com.cadence.common.error.CadenceException;
import com.cadence.common.error.ConflictException;
import com.cadence.common.error.NotFoundException;
import com.cadence.common.error.TooManyRequestsException;
import com.cadence.common.storage.ObjectStorage;
import com.cadence.events.HlsLayout;
import com.cadence.events.UuidV7;
import com.cadence.identity.Plan;
import com.cadence.identity.UserAccounts;
import com.cadence.streaming.domain.HlsPlaylists;
import com.cadence.streaming.infrastructure.FreePlanLimits;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Playback (spec 3.2). Manifests are served by the API because hls.js resolves variant and segment URIs relative to
 * the playlist: the master is filtered by plan and points at API-served variant playlists, whose segment URIs are
 * rewritten to presigned MinIO GETs, so audio bytes still go directly from storage to the player.
 */
@Service
public class PlaybackService {

    /** Spec 4: free users get at most 160 kbps. */
    static final int FREE_MAX_KBPS = 160;
    static final int PREMIUM_MAX_KBPS = 320;

    /** {@code adSlot}: set for free users every few tracks (D96); the client plays it before the track. */
    public record PlaybackStart(String manifestUrl, Instant expiresAt, Integer durationMs,
                                @JsonInclude(JsonInclude.Include.NON_NULL) AdSlot adSlot) {
    }

    /** Placeholder for an ad break: no ad content exists, the client shows a card for {@code durationMs}. */
    public record AdSlot(String type, long durationMs) {
    }

    /** {@code remaining}: skips left in the rolling window, or null for Premium (unlimited). */
    public record SkipResult(Integer remaining, int limit) {
    }

    private final CatalogQueries catalog;
    private final UserAccounts accounts;
    private final ObjectStorage storage;
    private final PlaybackTokens tokens;
    private final StreamingProperties properties;
    private final FreePlanLimits freePlan;
    private final Clock clock;

    PlaybackService(CatalogQueries catalog, UserAccounts accounts, ObjectStorage storage, PlaybackTokens tokens,
                    StreamingProperties properties, FreePlanLimits freePlan, Clock clock) {
        this.freePlan = freePlan;
        this.catalog = catalog;
        this.accounts = accounts;
        this.storage = storage;
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
    }

    public PlaybackStart start(UUID userId, UUID trackId, String baseUrl) {
        TrackSummary track = playableTrack(trackId);
        boolean premium = accounts.planOf(userId).orElse(Plan.FREE) == Plan.PREMIUM;
        int maxKbps = premium ? PREMIUM_MAX_KBPS : FREE_MAX_KBPS;
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.manifestUrlTtl());
        String token = tokens.mint(new PlaybackTokens.Grant(userId, trackId, maxKbps,
                track.durationMs() == null ? 0 : track.durationMs(), PlaybackTokens.MASTER, expiresAt), now);
        String url = baseUrl + "/api/v1/playback/" + trackId + "/" + HlsLayout.MASTER + "?token=" + encode(token);
        StreamingProperties.FreePlan rules = properties.freePlan();
        AdSlot adSlot = !premium && freePlan.adDue(userId, rules.adEvery())
                ? new AdSlot("placeholder", rules.adSlotDuration().toMillis()) : null;
        return new PlaybackStart(url, expiresAt, track.durationMs(), adSlot);
    }

    /**
     * A user-initiated skip of the playing track (spec 4: free users get 6 per hour). Premium is unlimited and nothing
     * is recorded. A retried request with the same {@code playId} doesn't count twice.
     *
     * @throws TooManyRequestsException ({@code skip-limit-reached}) with {@code Retry-After} on the 7th skip in an hour
     */
    public SkipResult skip(UUID userId, UUID trackId, UUID playId) {
        StreamingProperties.FreePlan rules = properties.freePlan();
        if (accounts.planOf(userId).orElse(Plan.FREE) == Plan.PREMIUM) {
            return new SkipResult(null, rules.skipsPerWindow());
        }
        String skipId = playId != null ? playId.toString() : trackId + ":" + UuidV7.generate();
        FreePlanLimits.SkipDecision decision = freePlan.skip(userId, skipId, rules.skipsPerWindow(), rules.skipWindow(),
                clock.instant());
        if (!decision.allowed()) {
            throw new TooManyRequestsException("skip-limit-reached", "Free plan: " + rules.skipsPerWindow()
                    + " skips per hour. Upgrade to Premium for unlimited skips.", decision.retryAfter().toSeconds());
        }
        return new SkipResult(decision.remaining(), rules.skipsPerWindow());
    }

    /** Master playlist with only the renditions the grant allows; variant URIs carry a media-scoped token. */
    public String masterPlaylist(UUID trackId, String token) {
        PlaybackTokens.Grant grant = tokens.verify(token, PlaybackTokens.MASTER, trackId);
        Instant now = clock.instant();
        Instant mediaExpiry = now.plus(sessionTtl(grant));
        String mediaToken = encode(tokens.mint(new PlaybackTokens.Grant(grant.userId(), trackId, grant.maxKbps(),
                grant.durationMs(), PlaybackTokens.MEDIA, mediaExpiry), now));
        return HlsPlaylists.filterMaster(read(HlsLayout.masterKey(trackId)), grant.maxKbps(),
                uri -> uri + "?token=" + mediaToken);
    }

    /** A rendition's media playlist with presigned segment URLs. */
    public String variantPlaylist(UUID trackId, String variant, String token) {
        PlaybackTokens.Grant grant = tokens.verify(token, PlaybackTokens.MEDIA, trackId);
        int kbps = HlsLayout.kbpsOf(variant);
        if (kbps < 0 || !HlsLayout.BITRATES_KBPS.contains(kbps)) {
            throw new NotFoundException("Rendition", variant);
        }
        if (kbps > grant.maxKbps()) {
            throw new CadenceException(HttpStatus.FORBIDDEN, "bitrate-not-allowed",
                    kbps + " kbps requires Premium (your limit is " + grant.maxKbps() + " kbps)");
        }
        Duration remaining = Duration.between(clock.instant(), grant.expiresAt());
        Duration ttl = remaining.compareTo(properties.segmentUrlTtl()) > 0 ? remaining : properties.segmentUrlTtl();
        String playlist = read(HlsLayout.variantKey(trackId, variant, HlsLayout.VARIANT_PLAYLIST));
        return HlsPlaylists.rewriteMedia(playlist, segment -> storage.presignGet(storage.hlsBucket(),
                HlsLayout.variantKey(trackId, variant, segment), ttl).toString());
    }

    TrackSummary playableTrack(UUID trackId) {
        TrackSummary track = catalog.findTrack(trackId).orElseThrow(() -> new NotFoundException("Track", trackId));
        if (!track.isPlayable()) {
            throw new ConflictException("track-not-playable", "Track is not ready for playback (status " + track.status() + ")");
        }
        return track;
    }

    private Duration sessionTtl(PlaybackTokens.Grant grant) {
        return properties.segmentUrlTtl().plusMillis(grant.durationMs());
    }

    private String read(String key) {
        try {
            return new String(storage.readAll(storage.hlsBucket(), key), StandardCharsets.UTF_8);
        } catch (NoSuchKeyException e) {
            throw new NotFoundException("Playlist", key);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
