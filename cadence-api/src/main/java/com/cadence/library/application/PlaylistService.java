package com.cadence.library.application;

import com.cadence.catalog.CatalogQueries;
import com.cadence.catalog.TrackSummary;
import com.cadence.common.error.BadRequestException;
import com.cadence.common.error.ConflictException;
import com.cadence.common.error.ForbiddenException;
import com.cadence.common.error.NotFoundException;
import com.cadence.common.error.PreconditionFailedException;
import com.cadence.common.pagination.Cursor;
import com.cadence.common.pagination.CursorPage;
import com.cadence.common.pagination.CursorRequest;
import com.cadence.events.EntityChangedPayload.Action;
import com.cadence.identity.UserAccounts;
import com.cadence.library.application.LibraryViews.CollaboratorView;
import com.cadence.library.application.LibraryViews.PlaylistDetail;
import com.cadence.library.application.LibraryViews.PlaylistItem;
import com.cadence.library.application.LibraryViews.PlaylistView;
import com.cadence.library.domain.FractionalIndex;
import com.cadence.library.domain.Playlist;
import com.cadence.library.domain.PlaylistCollaborator;
import com.cadence.library.domain.PlaylistTrack;
import com.cadence.library.domain.Visibility;
import com.cadence.library.infrastructure.PlaylistCollaboratorRepository;
import com.cadence.library.infrastructure.PlaylistRepository;
import com.cadence.library.infrastructure.PlaylistTrackRepository;
import org.springframework.data.domain.Limit;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Playlists (spec 4/5) and collaborative editing (D94). Every change bumps the playlist's version. Track changes update
 * the (version-checked) playlist row <em>before</em> touching track rows, so concurrent edits serialize on it and no
 * write is lost. A track change sent without {@code If-Match} is retried automatically when it loses that race (D95);
 * with {@code If-Match} the client asked for a precondition, so it gets 412 instead.
 */
@Service
public class PlaylistService {

    /** Spec Phase 3: "optimistic locking + retry". Attempts per track change before giving up with 409. */
    static final int MAX_ATTEMPTS = 10;
    static final int MAX_COLLABORATORS = 50;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PlaylistRepository playlists;
    private final PlaylistTrackRepository playlistTracks;
    private final PlaylistCollaboratorRepository collaborators;
    private final CatalogQueries catalog;
    private final LibraryMapper mapper;
    private final LibraryEvents events;
    private final UserAccounts accounts;
    private final TransactionTemplate tx;
    private final Clock clock;

    PlaylistService(PlaylistRepository playlists, PlaylistTrackRepository playlistTracks,
                    PlaylistCollaboratorRepository collaborators, CatalogQueries catalog, LibraryMapper mapper,
                    LibraryEvents events, UserAccounts accounts, PlatformTransactionManager transactions, Clock clock) {
        this.playlists = playlists;
        this.playlistTracks = playlistTracks;
        this.collaborators = collaborators;
        this.catalog = catalog;
        this.mapper = mapper;
        this.events = events;
        this.accounts = accounts;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /** Not idempotent: every call creates a playlist. */
    @Transactional
    public PlaylistView create(UUID ownerId, String name, String description, Visibility visibility, Boolean collaborative) {
        Playlist playlist = playlists.saveAndFlush(new Playlist(ownerId, name, description, visibility,
                Boolean.TRUE.equals(collaborative), clock.instant()));
        PlaylistView view = mapper.toView(playlist);
        events.playlistChanged(view, view.id(), Action.CREATED, view.createdAt());
        return view;
    }

    /** Playlists the caller owns or collaborates on, newest first (followed playlists: see DECISIONS.md D49). */
    @Transactional(readOnly = true)
    public CursorPage<PlaylistView> mine(UUID userId, CursorRequest page) {
        Limit limit = Limit.of(page.fetchSize());
        List<Playlist> rows = page.isFirstPage()
                ? playlists.findMine(userId, limit)
                : playlists.findMineAfter(userId, page.cursor().uuid(0), limit);
        return CursorPage.of(rows, page, p -> Cursor.of(p.getId())).map(mapper::toView);
    }

    @Transactional(readOnly = true)
    public PlaylistDetail get(UUID viewerId, UUID playlistId, CursorRequest page) {
        Playlist playlist = load(playlistId);
        Playlist.Role role = playlist.roleOf(viewerId, joined(playlist, viewerId));
        Limit limit = Limit.of(page.fetchSize());
        List<PlaylistTrack> rows = page.isFirstPage()
                ? playlistTracks.findByIdPlaylistIdOrderByPositionAsc(playlistId, limit)
                : playlistTracks.findPageAfter(playlistId, page.cursor().string(0), limit);
        CursorPage<PlaylistTrack> tracks = CursorPage.of(rows, page, t -> Cursor.of(t.getPosition()));
        Map<UUID, TrackSummary> summaries = catalog.findTracks(tracks.items().stream().map(PlaylistTrack::getTrackId).toList());
        CursorPage<PlaylistItem> items = tracks.map(t -> {
            TrackSummary summary = summaries.get(t.getTrackId());
            return new PlaylistItem(t.getTrackId(), summary, summary != null && summary.isPlayable(), t.getAddedBy(), t.getAddedAt());
        });
        PlaylistView v = mapper.toView(playlist);
        String ownerName = accounts.displayNames(List.of(v.ownerId())).get(v.ownerId());
        return new PlaylistDetail(v.id(), v.ownerId(), ownerName, v.name(), v.description(), v.coverUrl(), v.visibility(),
                v.collaborative(), v.trackCount(), v.version(), v.createdAt(), v.updatedAt(), role,
                (int) collaborators.countByIdPlaylistId(playlistId), items);
    }

    /** Turning {@code collaborative} off removes every collaborator and kills the invite link. */
    @Transactional
    public PlaylistView update(UUID userId, UUID playlistId, long expectedVersion, String name, String description,
                               Visibility visibility, Boolean collaborative) {
        Playlist playlist = load(playlistId);
        playlist.requireOwner(userId, joined(playlist, userId));
        requireVersion(playlist, expectedVersion);
        playlist.update(name, description, visibility, collaborative, clock.instant());
        flush(playlist, expectedVersion);
        if (Boolean.FALSE.equals(collaborative)) {
            collaborators.removeAll(playlistId);
        }
        PlaylistView view = mapper.toView(playlist);
        events.playlistChanged(view, view.id(), Action.UPDATED, view.updatedAt());
        return view;
    }

    @Transactional
    public void delete(UUID userId, UUID playlistId) {
        Playlist playlist = load(playlistId);
        playlist.requireOwner(userId, joined(playlist, userId));
        playlists.delete(playlist);
        events.playlistChanged(null, playlistId, Action.DELETED, clock.instant());
    }

    // ---- collaborators (D94) ----

    /** The invite link's token, created on first call (idempotent). Owner only; the playlist must be collaborative. */
    @Transactional
    public String invite(UUID userId, UUID playlistId) {
        Playlist playlist = load(playlistId);
        playlist.requireOwner(userId, joined(playlist, userId));
        String token = playlist.invite(PlaylistService::newInviteToken);
        playlists.saveAndFlush(playlist);
        return token;
    }

    /** Kills the invite link; people who already joined stay. Idempotent. */
    @Transactional
    public void revokeInvite(UUID userId, UUID playlistId) {
        Playlist playlist = load(playlistId);
        playlist.requireOwner(userId, joined(playlist, userId));
        if (playlist.getInviteToken() != null) {
            playlist.revokeInvite();
            playlists.saveAndFlush(playlist);
        }
    }

    /**
     * Joins a collaborative playlist with its invite token (idempotent; the owner joining is a no-op). A wrong or
     * revoked token is 403 {@code invalid-invite}, also for private playlists, so it doesn't reveal more than a 404.
     */
    @Transactional
    public PlaylistView join(UUID userId, UUID playlistId, String inviteToken) {
        Playlist playlist = load(playlistId);
        if (playlist.getOwnerId().equals(userId) || joined(playlist, userId)) {
            return mapper.toView(playlist);
        }
        if (!playlist.acceptsInvite(inviteToken)) {
            throw new ForbiddenException("invalid-invite",
                    "This invite link is not valid (it may have been revoked)");
        }
        if (collaborators.countByIdPlaylistId(playlistId) >= MAX_COLLABORATORS) {
            throw new ConflictException("too-many-collaborators",
                    "A playlist can have at most " + MAX_COLLABORATORS + " collaborators");
        }
        collaborators.insertIfAbsent(playlistId, userId, clock.instant());
        return mapper.toView(playlist);
    }

    /** Owner and collaborators can see who else edits the playlist. */
    @Transactional(readOnly = true)
    public List<CollaboratorView> collaborators(UUID userId, UUID playlistId) {
        Playlist playlist = load(playlistId);
        if (playlist.roleOf(userId, joined(playlist, userId)) == Playlist.Role.LISTENER) {
            throw new ForbiddenException("Only the owner and collaborators can see this");
        }
        List<PlaylistCollaborator> rows = collaborators.findByPlaylist(playlistId, Limit.of(MAX_COLLABORATORS));
        Map<UUID, String> names = accounts.displayNames(rows.stream().map(PlaylistCollaborator::getUserId).toList());
        return rows.stream().map(c -> new CollaboratorView(c.getUserId(), names.get(c.getUserId()), c.getJoinedAt()))
                .toList();
    }

    /** The owner removes anyone; a collaborator can remove themselves (leave). Idempotent. */
    @Transactional
    public void removeCollaborator(UUID userId, UUID playlistId, UUID collaboratorId) {
        Playlist playlist = load(playlistId);
        Playlist.Role role = playlist.roleOf(userId, joined(playlist, userId));
        if (role != Playlist.Role.OWNER && !userId.equals(collaboratorId)) {
            throw new ForbiddenException("Only the owner can remove other collaborators");
        }
        collaborators.remove(playlistId, collaboratorId);
    }

    // ---- tracks ----

    /**
     * Adds tracks in the given order at 0-based {@code position} (null = append). Tracks already in the playlist
     * are skipped, which makes the call idempotent. Only READY tracks can be added.
     */
    public PlaylistView addTracks(UUID userId, UUID playlistId, Long expectedVersion, List<UUID> trackIds, Integer position) {
        return retrying(expectedVersion, () -> doAddTracks(userId, playlistId, expectedVersion, trackIds, position));
    }

    /** Removes tracks; ids not in the playlist are ignored (idempotent). */
    public PlaylistView removeTracks(UUID userId, UUID playlistId, Long expectedVersion, List<UUID> trackIds) {
        return retrying(expectedVersion, () -> doRemoveTracks(userId, playlistId, expectedVersion, trackIds));
    }

    /** Moves {@code trackId} right after {@code afterTrackId} ({@code null} = to the top). Only the moved row changes. */
    public PlaylistView reorder(UUID userId, UUID playlistId, Long expectedVersion, UUID trackId, UUID afterTrackId) {
        return retrying(expectedVersion, () -> doReorder(userId, playlistId, expectedVersion, trackId, afterTrackId));
    }

    private PlaylistView doAddTracks(UUID userId, UUID playlistId, Long expectedVersion, List<UUID> trackIds,
                                     Integer position) {
        Playlist playlist = load(playlistId);
        playlist.requireEditor(userId, joined(playlist, userId));
        requireVersion(playlist, expectedVersion);
        Set<UUID> requested = new LinkedHashSet<>(trackIds);
        requireAddable(requested);
        playlistTracks.findByIdPlaylistIdAndIdTrackIdIn(playlistId, requested).forEach(t -> requested.remove(t.getTrackId()));
        if (requested.isEmpty()) {
            return mapper.toView(playlist);
        }
        long count = playlistTracks.countByIdPlaylistId(playlistId);
        if (count + requested.size() > Playlist.MAX_TRACKS) {
            throw new ConflictException("playlist-full", "A playlist can hold at most " + Playlist.MAX_TRACKS + " tracks");
        }
        Instant now = clock.instant();
        playlist.tracksChanged((int) count + requested.size(), now);
        flush(playlist, expectedVersion); // version check and row lock before reading neighbours

        long index = position == null ? count : Math.min(position, count);
        String before = index == 0 ? null : playlistTracks.positionAt(playlistId, index - 1).orElseThrow();
        String after = playlistTracks.positionAt(playlistId, index).orElse(null);
        List<String> keys = FractionalIndex.between(before, after, requested.size());
        List<PlaylistTrack> rows = new ArrayList<>();
        int i = 0;
        for (UUID trackId : requested) {
            rows.add(new PlaylistTrack(playlistId, trackId, keys.get(i++), userId, now));
        }
        playlistTracks.saveAllAndFlush(rows);
        return mapper.toView(playlist);
    }

    private PlaylistView doRemoveTracks(UUID userId, UUID playlistId, Long expectedVersion, List<UUID> trackIds) {
        Playlist playlist = load(playlistId);
        playlist.requireEditor(userId, joined(playlist, userId));
        requireVersion(playlist, expectedVersion);
        List<PlaylistTrack> present = playlistTracks.findByIdPlaylistIdAndIdTrackIdIn(playlistId, Set.copyOf(trackIds));
        if (present.isEmpty()) {
            return mapper.toView(playlist);
        }
        long count = playlistTracks.countByIdPlaylistId(playlistId);
        playlist.tracksChanged((int) count - present.size(), clock.instant());
        flush(playlist, expectedVersion);
        playlistTracks.deleteTracks(playlistId, present.stream().map(PlaylistTrack::getTrackId).collect(Collectors.toSet()));
        return mapper.toView(load(playlistId));
    }

    private PlaylistView doReorder(UUID userId, UUID playlistId, Long expectedVersion, UUID trackId, UUID afterTrackId) {
        Playlist playlist = load(playlistId);
        playlist.requireEditor(userId, joined(playlist, userId));
        requireVersion(playlist, expectedVersion);
        if (trackId.equals(afterTrackId)) {
            throw new BadRequestException("invalid-reorder", "A track can't be placed after itself");
        }
        PlaylistTrack moved = playlistTracks.findByIdPlaylistIdAndIdTrackId(playlistId, trackId)
                .orElseThrow(() -> new NotFoundException("Track in playlist", trackId));
        Optional<PlaylistTrack> anchor = afterTrackId == null ? Optional.empty()
                : Optional.of(playlistTracks.findByIdPlaylistIdAndIdTrackId(playlistId, afterTrackId)
                .orElseThrow(() -> new BadRequestException("unknown-after-track", "afterTrackId is not in the playlist")));
        playlist.tracksChanged(playlist.getTrackCount(), clock.instant());
        flush(playlist, expectedVersion);

        String before = anchor.map(PlaylistTrack::getPosition).orElse(null);
        List<String> next = before == null
                ? playlistTracks.firstPositions(playlistId, trackId, Limit.of(1))
                : playlistTracks.positionsAfter(playlistId, before, trackId, Limit.of(1));
        moved.moveTo(FractionalIndex.between(before, next.isEmpty() ? null : next.getFirst()));
        playlistTracks.flush();
        return mapper.toView(playlist);
    }

    /**
     * Runs a track change in its own transaction. Without {@code If-Match}, losing the race on the playlist row (or on
     * a concurrent insert of the same track or position) re-runs the whole change against the new state, with a short
     * random back-off, up to {@link #MAX_ATTEMPTS} times (D95). With {@code If-Match} it runs once.
     */
    private PlaylistView retrying(Long expectedVersion, Supplier<PlaylistView> change) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> change.get());
            } catch (ConcurrencyFailureException | DataIntegrityViolationException e) {
                if (expectedVersion != null) {
                    throw e instanceof ConcurrencyFailureException c ? c : conflict();
                }
                if (attempt >= MAX_ATTEMPTS) {
                    throw conflict();
                }
                backOff(attempt);
            }
        }
    }

    private static void backOff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5, 20L * attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw conflict();
        }
    }

    private static ConflictException conflict() {
        return new ConflictException("concurrent-modification",
                "The playlist was changed concurrently too many times; reload and retry");
    }

    private boolean joined(Playlist playlist, UUID userId) {
        return !playlist.getOwnerId().equals(userId) && playlist.isCollaborative()
                && collaborators.isCollaborator(playlist.getId(), userId);
    }

    private static String newInviteToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void requireAddable(Set<UUID> trackIds) {
        Map<UUID, TrackSummary> found = catalog.findTracks(trackIds);
        List<UUID> invalid = trackIds.stream().filter(id -> !found.containsKey(id) || !found.get(id).isPlayable()).toList();
        if (!invalid.isEmpty()) {
            throw new BadRequestException("unknown-track", "Tracks not found or not playable: " + invalid);
        }
    }

    private static void requireVersion(Playlist playlist, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion != playlist.getVersion()) {
            throw new PreconditionFailedException("Playlist is at version " + playlist.getVersion()
                    + ", not " + expectedVersion + "; reload and retry");
        }
    }

    /** A concurrent writer wins → 412 if the caller sent If-Match, otherwise 409 (GlobalExceptionHandler). */
    private void flush(Playlist playlist, Long expectedVersion) {
        try {
            playlists.saveAndFlush(playlist);
        } catch (ObjectOptimisticLockingFailureException e) {
            if (expectedVersion != null) {
                throw new PreconditionFailedException("Playlist was modified concurrently; reload and retry");
            }
            throw e;
        }
    }

    private Playlist load(UUID id) {
        return playlists.findById(id).orElseThrow(() -> new NotFoundException("Playlist", id));
    }
}
