package com.cadence.catalog.application;

import com.cadence.catalog.TrackStatus;
import com.cadence.catalog.application.CatalogViews.AdminTrackView;
import com.cadence.catalog.domain.Album;
import com.cadence.catalog.domain.ArtistRole;
import com.cadence.catalog.domain.Track;
import com.cadence.catalog.domain.TrackArtist;
import com.cadence.catalog.infrastructure.AlbumRepository;
import com.cadence.catalog.infrastructure.ArtistRepository;
import com.cadence.catalog.infrastructure.TrackRepository;
import com.cadence.common.error.BadRequestException;
import com.cadence.common.error.NotFoundException;
import com.cadence.common.pagination.Cursor;
import com.cadence.common.pagination.CursorPage;
import com.cadence.common.pagination.CursorRequest;
import com.cadence.common.storage.ObjectStorage;
import com.cadence.events.EntityChangedPayload.Action;
import com.cadence.events.ItemTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TrackAdminService {

    private static final Logger log = LoggerFactory.getLogger(TrackAdminService.class);

    public record Credit(UUID artistId, ArtistRole role) {
    }

    private final TrackRepository tracks;
    private final AlbumRepository albums;
    private final ArtistRepository artists;
    private final TrackSummaries summaries;
    private final CatalogMapper mapper;
    private final CatalogEvents events;
    private final ObjectStorage storage;
    private final Clock clock;

    TrackAdminService(TrackRepository tracks, AlbumRepository albums, ArtistRepository artists, TrackSummaries summaries,
                      CatalogMapper mapper, CatalogEvents events, ObjectStorage storage, Clock clock) {
        this.tracks = tracks;
        this.albums = albums;
        this.artists = artists;
        this.summaries = summaries;
        this.mapper = mapper;
        this.events = events;
        this.storage = storage;
        this.clock = clock;
    }

    /** Creates a DRAFT track. Without explicit credits the album artist becomes the PRIMARY artist. */
    @Transactional
    public AdminTrackView create(String title, UUID albumId, int trackNumber, Integer discNumber, Boolean explicit,
                                 String isrc, List<Credit> credits) {
        Album album = albums.findById(albumId)
                .orElseThrow(() -> new BadRequestException("unknown-album", "Album " + albumId + " does not exist"));
        List<TrackArtist> resolved = credits == null || credits.isEmpty()
                ? List.of(new TrackArtist(album.getArtistId(), ArtistRole.PRIMARY))
                : toCredits(credits);
        Track track = tracks.save(new Track(title, albumId, trackNumber, discNumber == null ? 1 : discNumber,
                Boolean.TRUE.equals(explicit), isrc, resolved, clock.instant()));
        tracks.flush();
        events.entityChanged(ItemTypes.SONG, track.getId(), Action.CREATED, summaries.snapshot(track));
        return mapper.toAdminView(track);
    }

    @Transactional
    public AdminTrackView update(UUID id, String title, Integer trackNumber, Integer discNumber, Boolean explicit,
                                 String isrc, List<Credit> credits) {
        Track track = load(id);
        track.update(title, trackNumber, discNumber, explicit, isrc, credits == null ? null : toCredits(credits),
                clock.instant());
        tracks.flush();
        events.entityChanged(ItemTypes.SONG, id, Action.UPDATED, summaries.snapshot(track));
        return mapper.toAdminView(track);
    }

    /** Deletes the track; its raw upload and HLS output are removed after commit (best effort). */
    @Transactional
    public void delete(UUID id) {
        Track track = load(id);
        tracks.delete(track);
        events.entityChanged(ItemTypes.SONG, id, Action.DELETED, null);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    storage.deletePrefix(storage.rawBucket(), "raw/" + id + "/");
                    storage.deletePrefix(storage.hlsBucket(), "hls/" + id + "/");
                } catch (RuntimeException e) {
                    log.warn("Could not delete stored audio of track {}: {}", id, e.toString());
                }
            }
        });
    }

    @Transactional(readOnly = true)
    public AdminTrackView get(UUID id) {
        return mapper.toAdminView(load(id));
    }

    /** Processing dashboard: most recently changed first, optionally filtered by status. */
    @Transactional(readOnly = true)
    public CursorPage<AdminTrackView> list(TrackStatus status, CursorRequest page) {
        Limit limit = Limit.of(page.fetchSize());
        List<Track> rows = page.isFirstPage()
                ? tracks.findForAdmin(status, limit)
                : tracks.findForAdminAfter(status, page.cursor().instant(0), page.cursor().uuid(1), limit);
        return CursorPage.of(rows, page, t -> Cursor.of(t.getUpdatedAt(), t.getId())).map(mapper::toAdminView);
    }

    private List<TrackArtist> toCredits(List<Credit> credits) {
        Set<UUID> ids = credits.stream().map(Credit::artistId).collect(Collectors.toSet());
        Set<UUID> missing = new HashSet<>(ids);
        artists.findAllById(ids).forEach(a -> missing.remove(a.getId()));
        if (!missing.isEmpty()) {
            throw new BadRequestException("unknown-artist", "Unknown artist(s): " + missing);
        }
        return credits.stream().map(c -> new TrackArtist(c.artistId(), c.role())).toList();
    }

    Track load(UUID id) {
        return tracks.findById(id).orElseThrow(() -> new NotFoundException("Track", id));
    }
}
