package com.cadence.library.application;

import com.cadence.catalog.CatalogQueries;
import com.cadence.catalog.CatalogRefs.AlbumRef;
import com.cadence.catalog.TrackSummary;
import com.cadence.common.error.NotFoundException;
import com.cadence.common.pagination.Cursor;
import com.cadence.common.pagination.CursorPage;
import com.cadence.common.pagination.CursorRequest;
import com.cadence.library.application.LibraryViews.LikedTrackView;
import com.cadence.library.application.LibraryViews.SavedAlbumView;
import com.cadence.library.domain.LikedTrack;
import com.cadence.library.domain.SavedAlbum;
import com.cadence.library.infrastructure.FollowedArtistRepository;
import com.cadence.library.infrastructure.LikedTrackRepository;
import com.cadence.library.infrastructure.SavedAlbumRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Likes, follows and saved albums. All writes are idempotent; events fire only on actual state changes. */
@Service
public class LibraryService {

    private final LikedTrackRepository likes;
    private final FollowedArtistRepository follows;
    private final SavedAlbumRepository savedAlbums;
    private final CatalogQueries catalog;
    private final LibraryEvents events;
    private final Clock clock;

    LibraryService(LikedTrackRepository likes, FollowedArtistRepository follows, SavedAlbumRepository savedAlbums,
                   CatalogQueries catalog, LibraryEvents events, Clock clock) {
        this.likes = likes;
        this.follows = follows;
        this.savedAlbums = savedAlbums;
        this.catalog = catalog;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void like(UUID userId, UUID trackId) {
        catalog.findTrack(trackId).filter(TrackSummary::isPlayable).orElseThrow(() -> new NotFoundException("Track", trackId));
        Instant now = clock.instant();
        if (likes.like(userId, trackId, now) == 1) {
            events.trackLiked(userId, trackId, true, now);
        }
    }

    @Transactional
    public void unlike(UUID userId, UUID trackId) {
        Instant now = clock.instant();
        if (likes.unlike(userId, trackId) == 1) {
            events.trackLiked(userId, trackId, false, now);
        }
    }

    /** Most recently liked first. */
    @Transactional(readOnly = true)
    public CursorPage<LikedTrackView> likedTracks(UUID userId, CursorRequest page) {
        Limit limit = Limit.of(page.fetchSize());
        List<LikedTrack> rows = page.isFirstPage()
                ? likes.firstPage(userId, limit)
                : likes.pageAfter(userId, page.cursor().instant(0), page.cursor().uuid(1), limit);
        CursorPage<LikedTrack> result = CursorPage.of(rows, page, l -> Cursor.of(l.getLikedAt(), l.getTrackId()));
        Map<UUID, TrackSummary> tracks = catalog.findTracks(result.items().stream().map(LikedTrack::getTrackId).toList());
        return result.map(l -> new LikedTrackView(tracks.get(l.getTrackId()), l.getLikedAt()));
    }

    @Transactional
    public void follow(UUID userId, UUID artistId) {
        if (!catalog.artistExists(artistId)) {
            throw new NotFoundException("Artist", artistId);
        }
        Instant now = clock.instant();
        if (follows.follow(userId, artistId, now) == 1) {
            events.artistFollowed(userId, artistId, true, now);
        }
    }

    @Transactional
    public void unfollow(UUID userId, UUID artistId) {
        Instant now = clock.instant();
        if (follows.unfollow(userId, artistId) == 1) {
            events.artistFollowed(userId, artistId, false, now);
        }
    }

    @Transactional
    public void saveAlbum(UUID userId, UUID albumId) {
        if (!catalog.albumExists(albumId)) {
            throw new NotFoundException("Album", albumId);
        }
        savedAlbums.save(userId, albumId, clock.instant());
    }

    @Transactional
    public void unsaveAlbum(UUID userId, UUID albumId) {
        savedAlbums.unsave(userId, albumId);
    }

    @Transactional(readOnly = true)
    public CursorPage<SavedAlbumView> savedAlbums(UUID userId, CursorRequest page) {
        Limit limit = Limit.of(page.fetchSize());
        List<SavedAlbum> rows = page.isFirstPage()
                ? savedAlbums.firstPage(userId, limit)
                : savedAlbums.pageAfter(userId, page.cursor().instant(0), page.cursor().uuid(1), limit);
        CursorPage<SavedAlbum> result = CursorPage.of(rows, page, s -> Cursor.of(s.getSavedAt(), s.getAlbumId()));
        Map<UUID, AlbumRef> albums = catalog.findAlbums(result.items().stream().map(SavedAlbum::getAlbumId).toList());
        return result.map(s -> new SavedAlbumView(albums.get(s.getAlbumId()), s.getSavedAt()));
    }
}
