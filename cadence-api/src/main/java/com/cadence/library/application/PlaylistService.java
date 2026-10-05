package com.cadence.library.application;

import com.cadence.catalog.CatalogQueries;
import com.cadence.catalog.TrackSummary;
import com.cadence.common.error.BadRequestException;
import com.cadence.common.error.ConflictException;
import com.cadence.common.error.NotFoundException;
import com.cadence.common.error.PreconditionFailedException;
import com.cadence.common.pagination.Cursor;
import com.cadence.common.pagination.CursorPage;
import com.cadence.common.pagination.CursorRequest;
import com.cadence.events.EntityChangedPayload.Action;
import com.cadence.library.application.LibraryViews.PlaylistDetail;
import com.cadence.library.application.LibraryViews.PlaylistItem;
import com.cadence.library.application.LibraryViews.PlaylistView;
import com.cadence.library.domain.FractionalIndex;
import com.cadence.library.domain.Playlist;
import com.cadence.library.domain.PlaylistTrack;
import com.cadence.library.domain.Visibility;
import com.cadence.library.infrastructure.PlaylistRepository;
import com.cadence.library.infrastructure.PlaylistTrackRepository;
import org.springframework.data.domain.Limit;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Playlists (spec 4/5). Every change bumps the playlist's version. Track changes update the (version-checked)
 * playlist row <em>before</em> touching track rows, so concurrent edits serialize on it: the loser gets a
 * conflict instead of silently overwriting (no lost writes).
 */
@Service
public class PlaylistService {

    private final PlaylistRepository playlists;
    private final PlaylistTrackRepository playlistTracks;
    private final CatalogQueries catalog;
    private final LibraryMapper mapper;
    private final LibraryEvents events;
    private final Clock clock;

    PlaylistService(PlaylistRepository playlists, PlaylistTrackRepository playlistTracks, CatalogQueries catalog,
                    LibraryMapper mapper, LibraryEvents events, Clock clock) {
        this.playlists = playlists;
        this.playlistTracks = playlistTracks;
        this.catalog = catalog;
        this.mapper = mapper;
        this.events = events;
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

    /** The caller's own playlists, newest first (followed playlists: see DECISIONS.md D49). */
    @Transactional(readOnly = true)
    public CursorPage<PlaylistView> mine(UUID ownerId, CursorRequest page) {
        Limit limit = Limit.of(page.fetchSize());
        List<Playlist> rows = page.isFirstPage()
                ? playlists.findByOwnerIdOrderByIdDesc(ownerId, limit)
                : playlists.findByOwnerAfter(ownerId, page.cursor().uuid(0), limit);
        return CursorPage.of(rows, page, p -> Cursor.of(p.getId())).map(mapper::toView);
    }

    @Transactional(readOnly = true)
    public PlaylistDetail get(UUID viewerId, UUID playlistId, CursorRequest page) {
        Playlist playlist = load(playlistId);
        playlist.requireVisibleTo(viewerId);
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
        return new PlaylistDetail(v.id(), v.ownerId(), v.name(), v.description(), v.coverUrl(), v.visibility(),
                v.collaborative(), v.trackCount(), v.version(), v.createdAt(), v.updatedAt(), items);
    }

    @Transactional
    public PlaylistView update(UUID userId, UUID playlistId, long expectedVersion, String name, String description,
                               Visibility visibility, Boolean collaborative) {
        Playlist playlist = load(playlistId);
        playlist.requireOwner(userId);
        requireVersion(playlist, expectedVersion);
        playlist.update(name, description, visibility, collaborative, clock.instant());
        flush(playlist, expectedVersion);
        PlaylistView view = mapper.toView(playlist);
        events.playlistChanged(view, view.id(), Action.UPDATED, view.updatedAt());
        return view;
    }

    @Transactional
    public void delete(UUID userId, UUID playlistId) {
        Playlist playlist = load(playlistId);
        playlist.requireOwner(userId);
        playlists.delete(playlist);
        events.playlistChanged(null, playlistId, Action.DELETED, clock.instant());
    }

    /**
     * Adds tracks in the given order at 0-based {@code position} (null = append). Tracks already in the playlist
     * are skipped, which makes the call idempotent. Only READY tracks can be added.
     */
    @Transactional
    public PlaylistView addTracks(UUID userId, UUID playlistId, Long expectedVersion, List<UUID> trackIds, Integer position) {
        Playlist playlist = load(playlistId);
        playlist.requireEditor(userId);
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

    /** Removes tracks; ids not in the playlist are ignored (idempotent). */
    @Transactional
    public PlaylistView removeTracks(UUID userId, UUID playlistId, Long expectedVersion, List<UUID> trackIds) {
        Playlist playlist = load(playlistId);
        playlist.requireEditor(userId);
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

    /** Moves {@code trackId} right after {@code afterTrackId} ({@code null} = to the top). Only the moved row changes. */
    @Transactional
    public PlaylistView reorder(UUID userId, UUID playlistId, Long expectedVersion, UUID trackId, UUID afterTrackId) {
        Playlist playlist = load(playlistId);
        playlist.requireEditor(userId);
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
