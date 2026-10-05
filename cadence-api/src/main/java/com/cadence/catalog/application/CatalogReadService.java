package com.cadence.catalog.application;

import com.cadence.catalog.AlbumSummary;
import com.cadence.catalog.CatalogQueries;
import com.cadence.catalog.CatalogRefs.ArtistRef;
import com.cadence.catalog.TrackStatus;
import com.cadence.catalog.TrackSummary;
import com.cadence.catalog.application.CatalogViews.AlbumDetail;
import com.cadence.catalog.application.CatalogViews.AlbumView;
import com.cadence.catalog.application.CatalogViews.ArtistDetail;
import com.cadence.catalog.application.CatalogViews.GenreView;
import com.cadence.catalog.application.CatalogViews.TrackDetail;
import com.cadence.catalog.domain.Album;
import com.cadence.catalog.domain.Artist;
import com.cadence.catalog.domain.Genre;
import com.cadence.catalog.domain.Track;
import com.cadence.catalog.infrastructure.AlbumRepository;
import com.cadence.catalog.infrastructure.ArtistRepository;
import com.cadence.catalog.infrastructure.GenreRepository;
import com.cadence.catalog.infrastructure.TrackRepository;
import com.cadence.common.error.NotFoundException;
import com.cadence.common.pagination.Cursor;
import com.cadence.common.pagination.CursorPage;
import com.cadence.common.pagination.CursorRequest;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Public catalog reads. Listeners only ever see READY tracks. */
@Service
@Transactional(readOnly = true)
public class CatalogReadService implements CatalogQueries {

    private static final int TOP_TRACKS = 10;

    private final ArtistRepository artists;
    private final AlbumRepository albums;
    private final TrackRepository tracks;
    private final GenreRepository genres;
    private final TrackSummaries summaries;
    private final CatalogMapper mapper;
    private final Clock clock;

    CatalogReadService(ArtistRepository artists, AlbumRepository albums, TrackRepository tracks, GenreRepository genres,
                       TrackSummaries summaries, CatalogMapper mapper, Clock clock) {
        this.artists = artists;
        this.albums = albums;
        this.tracks = tracks;
        this.genres = genres;
        this.summaries = summaries;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Cacheable(cacheNames = CatalogCaches.ARTISTS, key = "#id")
    public ArtistDetail artist(UUID id) {
        Artist artist = artists.findById(id).orElseThrow(() -> new NotFoundException("Artist", id));
        List<TrackSummary> top = summaries.of(tracks.findTopReadyByArtist(id, Limit.of(TOP_TRACKS)));
        return new ArtistDetail(artist.getId(), artist.getName(), artist.getBio(), artist.getImageUrl(),
                artist.isVerified(), artist.getMonthlyListeners(), top);
    }

    /** Newest release first. */
    public CursorPage<AlbumView> artistAlbums(UUID artistId, CursorRequest page) {
        Artist artist = artists.findById(artistId).orElseThrow(() -> new NotFoundException("Artist", artistId));
        Limit limit = Limit.of(page.fetchSize());
        List<Album> rows = page.isFirstPage()
                ? albums.findByArtistIdOrderByReleaseDateDescIdDesc(artistId, limit)
                : albums.findByArtistAfter(artistId, java.time.LocalDate.parse(page.cursor().string(0)),
                page.cursor().uuid(1), limit);
        ArtistRef ref = new ArtistRef(artist.getId(), artist.getName());
        return CursorPage.of(rows, page, a -> Cursor.of(a.getReleaseDate(), a.getId())).map(a -> mapper.toView(a, ref));
    }

    @Cacheable(cacheNames = CatalogCaches.ALBUMS, key = "#id")
    public AlbumDetail album(UUID id) {
        Album album = albums.findById(id).orElseThrow(() -> new NotFoundException("Album", id));
        Artist artist = artists.getReferenceById(album.getArtistId());
        List<TrackSummary> tracklist = summaries.of(
                tracks.findByAlbumIdAndStatusOrderByDiscNumberAscTrackNumberAsc(id, TrackStatus.READY));
        long total = tracklist.stream().map(TrackSummary::durationMs).filter(Objects::nonNull).mapToLong(Integer::longValue).sum();
        return new AlbumDetail(album.getId(), album.getTitle(), album.getType(), album.getReleaseDate(),
                album.getCoverUrl(), album.getLabel(), new ArtistRef(artist.getId(), artist.getName()),
                album.getGenreNames(), total, tracklist);
    }

    /** 404 unless the track is READY: drafts and failed uploads are not part of the public catalog. */
    public TrackDetail track(UUID id) {
        Track track = tracks.findById(id).filter(t -> t.getStatus() == TrackStatus.READY)
                .orElseThrow(() -> new NotFoundException("Track", id));
        TrackSummary summary = summaries.of(track);
        return new TrackDetail(track.getId(), track.getTitle(), track.getDurationMs(), track.isExplicit(),
                track.getDiscNumber(), track.getTrackNumber(), track.getIsrc(), track.getPlayCount(),
                summary.album(), summary.artists());
    }

    /** Alphabetical. */
    public CursorPage<GenreView> genres(CursorRequest page) {
        Limit limit = Limit.of(page.fetchSize());
        List<Genre> rows = page.isFirstPage()
                ? genres.findAllByOrderByNameAsc(limit)
                : genres.findByNameGreaterThanOrderByNameAsc(page.cursor().string(0), limit);
        return CursorPage.of(rows, page, g -> Cursor.of(g.getName())).map(mapper::toView);
    }

    // ---- CatalogQueries (public API for other contexts) ----

    @Override
    public Optional<TrackSummary> findTrack(UUID trackId) {
        return tracks.findById(trackId).map(summaries::of);
    }

    @Override
    public Map<UUID, TrackSummary> findTracks(Collection<UUID> trackIds) {
        if (trackIds.isEmpty()) {
            return Map.of();
        }
        return summaries.of(tracks.findAllById(trackIds)).stream()
                .collect(Collectors.toMap(TrackSummary::id, Function.identity()));
    }

    @Override
    public Map<UUID, com.cadence.catalog.CatalogRefs.AlbumRef> findAlbums(Collection<UUID> albumIds) {
        if (albumIds.isEmpty()) {
            return Map.of();
        }
        return albums.findAllById(albumIds).stream().collect(Collectors.toMap(Album::getId,
                a -> new com.cadence.catalog.CatalogRefs.AlbumRef(a.getId(), a.getTitle(), a.getCoverUrl())));
    }

    @Override
    public Map<UUID, ArtistRef> findArtists(Collection<UUID> artistIds) {
        if (artistIds.isEmpty()) {
            return Map.of();
        }
        return artists.findAllById(artistIds).stream()
                .collect(Collectors.toMap(Artist::getId, a -> new ArtistRef(a.getId(), a.getName())));
    }

    @Override
    public List<AlbumSummary> newReleases(int limit) {
        List<Album> rows = albums.findNewReleases(LocalDate.now(clock), Limit.of(limit));
        Map<UUID, Artist> artistById = artists.findAllById(rows.stream().map(Album::getArtistId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Artist::getId, Function.identity()));
        return rows.stream().map(a -> new AlbumSummary(a.getId(), a.getTitle(), a.getType().name(), a.getReleaseDate(),
                a.getCoverUrl(), new ArtistRef(a.getArtistId(), artistById.get(a.getArtistId()).getName()))).toList();
    }

    @Override
    public boolean artistExists(UUID artistId) {
        return artists.existsById(artistId);
    }

    @Override
    public boolean albumExists(UUID albumId) {
        return albums.existsById(albumId);
    }
}
