import { useEffect, useState } from 'react';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { api } from '../api/endpoints';
import { keys } from '../api/hooks';
import type { SearchResults, TrackSummary } from '../api/types';
import { useScrolled } from '../components/AppShell';
import { Artwork } from '../components/Artwork';
import { AlbumCard, ArtistCard, PlaylistCard, Section } from '../components/Cards';
import { PauseIcon, PlayIcon } from '../components/Icons';
import { SearchBox } from '../components/SearchBox';
import { TopBar } from '../components/TopBar';
import { TrackList } from '../components/TrackList';
import { artistNames, plural, year } from '../lib/format';
import { usePlayer, usePlayerActions } from '../player/PlayerProvider';
import type { QueueContext } from '../player/queue';

type Filter = 'all' | 'track' | 'artist' | 'album' | 'playlist';
const FILTERS: [Filter, string][] = [['all', 'All'], ['track', 'Songs'], ['artist', 'Artists'], ['album', 'Albums'], ['playlist', 'Playlists']];
const RECENT_KEY = 'cadence.recentSearches';

function recentSearches(): string[] {
  try {
    return JSON.parse(localStorage.getItem(RECENT_KEY) ?? '[]') as string[];
  } catch {
    return [];
  }
}

function remember(q: string) {
  try {
    localStorage.setItem(RECENT_KEY, JSON.stringify([q, ...recentSearches().filter((r) => r !== q)].slice(0, 8)));
  } catch {
    // ignore
  }
}

function TopResult({ results, context }: { results: SearchResults; context: QueueContext }) {
  const navigate = useNavigate();
  const player = usePlayerActions();
  const { current, isPlaying } = usePlayer();
  const q = results.query.toLowerCase();
  const artist = results.artists?.items[0];
  const track = results.tracks?.items[0];
  const album = results.albums?.items[0];
  const artistFirst = artist && (!track || artist.name.toLowerCase().split(/\s+/).some((w) => q.split(/\s+/).some((t) => w.startsWith(t.slice(0, 3)))));

  if (artistFirst && artist) {
    return (
      <div className="top-result" onClick={() => navigate(`/artist/${artist.id}`)} role="link" tabIndex={0}>
        <Artwork seed={artist.id} title={artist.name} src={artist.imageUrl} round />
        <div><h3>{artist.name}</h3><div style={{ marginTop: 8 }}><span className="pill">Artist</span></div></div>
        <button type="button" className="play-fab" aria-label={`Play ${artist.name}`} onClick={async (e) => {
          e.stopPropagation();
          const detail = await api.artist(artist.id);
          player.playTracks(detail.topTracks, 0, { source: 'ARTIST', sourceId: artist.id, label: artist.name, href: `/artist/${artist.id}` });
        }}><PlayIcon /></button>
      </div>
    );
  }
  if (track) {
    const tracks = results.tracks!.items;
    const isCurrent = current?.id === track.id;
    return (
      <div className="top-result" onClick={() => track.album && navigate(`/album/${track.album.id}`)} role="link" tabIndex={0}>
        <Artwork seed={track.album?.id ?? track.id} title={track.album?.title ?? track.title} src={track.album?.coverUrl} />
        <div>
          <h3>{track.title}</h3>
          <div style={{ marginTop: 8, display: 'flex', gap: 8, alignItems: 'center' }} className="muted">
            <span className="pill">Song</span>{artistNames(track.artists)}
          </div>
        </div>
        <button type="button" className="play-fab" aria-label={`Play ${track.title}`} onClick={(e) => {
          e.stopPropagation();
          if (isCurrent) player.togglePlay();
          else player.playTracks(tracks, 0, context);
        }}>{isCurrent && isPlaying ? <PauseIcon /> : <PlayIcon />}</button>
      </div>
    );
  }
  if (album) {
    return (
      <div className="top-result" onClick={() => navigate(`/album/${album.id}`)} role="link" tabIndex={0}>
        <Artwork seed={album.id} title={album.title} src={album.coverUrl} />
        <div><h3>{album.title}</h3><div style={{ marginTop: 8 }} className="muted"><span className="pill">Album</span> {album.artist.name}</div></div>
      </div>
    );
  }
  return null;
}

function SingleType({ q, type, context }: { q: string; type: Exclude<Filter, 'all'>; context: QueueContext }) {
  const query = useInfiniteQuery({
    queryKey: ['search', q, type],
    queryFn: ({ pageParam }) => api.search(q, type, 30, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => {
      const group = last.tracks ?? last.artists ?? last.albums ?? last.playlists;
      return group?.nextCursor ?? undefined;
    },
  });
  const pages = query.data?.pages ?? [];
  const tracks = pages.flatMap((p) => p.tracks?.items ?? []);
  const artists = pages.flatMap((p) => p.artists?.items ?? []);
  const albums = pages.flatMap((p) => p.albums?.items ?? []);
  const playlists = pages.flatMap((p) => p.playlists?.items ?? []);
  const empty = query.isSuccess && tracks.length + artists.length + albums.length + playlists.length === 0;

  return (
    <div className="section" style={{ marginTop: 20 }}>
      {type === 'track' && tracks.length > 0 && <TrackList items={tracks.map((t) => ({ track: t }))} context={context} />}
      {type === 'artist' && <div className="grid-cards">{artists.map((a) => <ArtistCard key={a.id} id={a.id} name={a.name} imageUrl={a.imageUrl} />)}</div>}
      {type === 'album' && <div className="grid-cards">{albums.map((a) => <AlbumCard key={a.id} id={a.id} title={a.title} coverUrl={a.coverUrl} subtitle={`${year(a.releaseDate)} • ${a.artist.name}`} />)}</div>}
      {type === 'playlist' && <div className="grid-cards">{playlists.map((p) => <PlaylistCard key={p.id} id={p.id} name={p.name} coverUrl={p.coverUrl} subtitle={`By ${p.owner.displayName ?? 'someone'}`} />)}</div>}
      {empty && <NoResults q={q} />}
      {query.hasNextPage && (
        <div style={{ display: 'flex', justifyContent: 'center', marginTop: 20 }}>
          <button type="button" className="btn btn-ghost" onClick={() => query.fetchNextPage()} disabled={query.isFetchingNextPage}>
            {query.isFetchingNextPage ? 'Loading…' : 'Load more'}
          </button>
        </div>
      )}
    </div>
  );
}

function NoResults({ q }: { q: string }) {
  return (
    <div className="empty">
      <h2>No results found for “{q}”</h2>
      <p>Check the spelling, or try fewer or different keywords.</p>
    </div>
  );
}

function Browse() {
  const navigate = useNavigate();
  const [recent, setRecent] = useState(recentSearches);
  const home = useQuery({ queryKey: keys.home, queryFn: api.home, staleTime: 30_000 });
  const releases = home.data?.shelves.find((s) => s.id === 'new-releases')?.items ?? [];
  return (
    <>
      {recent.length > 0 && (
        <Section title="Recent searches">
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
            {recent.map((r) => <button key={r} type="button" className="chip" onClick={() => navigate(`/search?q=${encodeURIComponent(r)}`)}>{r}</button>)}
            <button type="button" className="btn btn-quiet btn-sm" onClick={() => { localStorage.removeItem(RECENT_KEY); setRecent([]); }}>Clear</button>
          </div>
        </Section>
      )}
      <Section title="Fresh releases">
        <div className="browse-grid">
          {releases.flatMap((i) => (i.type === 'album' ? [i.album] : [])).map((a) => (
            <Link key={a.id} to={`/album/${a.id}`} className="browse-tile"
                  style={{ background: `hsl(${(a.title.length * 47 + a.id.charCodeAt(0) * 13) % 360} 55% 38%)` }}>
              {a.title}
              <Artwork seed={a.id} title={a.title} src={a.coverUrl} />
            </Link>
          ))}
        </div>
      </Section>
    </>
  );
}

export function SearchPage() {
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const filter = (params.get('type') as Filter | null) ?? 'all';
  const [input, setInput] = useState(q);
  const scrolled = useScrolled(10);
  useEffect(() => setInput(q), [q]);

  const all = useQuery({
    queryKey: ['search', q, 'all'],
    queryFn: () => api.search(q, undefined, 10),
    enabled: q.length > 0 && filter === 'all',
    placeholderData: (previous) => previous,
  });
  useEffect(() => {
    if (q) remember(q);
  }, [q]);

  const context: QueueContext = { source: 'SEARCH', sourceId: null, label: `Search: ${q}`, href: `/search?q=${encodeURIComponent(q)}` };
  const submit = (value: string) => setParams(value ? { q: value } : {});
  const results = all.data;
  const total = results ? (results.tracks?.items.length ?? 0) + (results.artists?.items.length ?? 0)
    + (results.albums?.items.length ?? 0) + (results.playlists?.items.length ?? 0) : 0;
  const songs: TrackSummary[] = results?.tracks?.items ?? [];

  return (
    <>
      <TopBar solid={scrolled}>
        <SearchBox value={input} onChange={setInput} onSubmit={submit} autoFocus={!q} />
      </TopBar>
      <div className="page">
        {q && (
          <div style={{ display: 'flex', gap: 8, marginTop: 4 }} role="tablist" aria-label="Result type">
            {FILTERS.map(([value, label]) => (
              <button key={value} type="button" role="tab" aria-selected={filter === value} className={`chip${filter === value ? ' active' : ''}`}
                      onClick={() => setParams(value === 'all' ? { q } : { q, type: value })}>{label}</button>
            ))}
          </div>
        )}
        {!q && <Browse />}
        {q && filter !== 'all' && <SingleType key={`${q}-${filter}`} q={q} type={filter} context={context} />}
        {q && filter === 'all' && results && total === 0 && <NoResults q={q} />}
        {q && filter === 'all' && results && total > 0 && (
          <>
            <div className="results-grid section" style={{ marginTop: 20 }}>
              <div>
                <h2 className="section-title" style={{ marginBottom: 14 }}>Top result</h2>
                <TopResult results={results} context={context} />
              </div>
              {songs.length > 0 && (
                <div style={{ minWidth: 0 }}>
                  <h2 className="section-title" style={{ marginBottom: 6 }}>Songs</h2>
                  <TrackList items={songs.slice(0, 4).map((t) => ({ track: t }))} context={context} showAlbum={false} />
                </div>
              )}
            </div>
            {(results.artists?.items.length ?? 0) > 0 && (
              <Section title="Artists">
                <div className="shelf">{results.artists!.items.map((a) => <ArtistCard key={a.id} id={a.id} name={a.name} imageUrl={a.imageUrl} />)}</div>
              </Section>
            )}
            {(results.albums?.items.length ?? 0) > 0 && (
              <Section title="Albums">
                <div className="shelf">{results.albums!.items.map((a) => <AlbumCard key={a.id} id={a.id} title={a.title} coverUrl={a.coverUrl} subtitle={`${year(a.releaseDate)} • ${a.artist.name}`} />)}</div>
              </Section>
            )}
            {(results.playlists?.items.length ?? 0) > 0 && (
              <Section title="Playlists">
                <div className="shelf">{results.playlists!.items.map((p) => <PlaylistCard key={p.id} id={p.id} name={p.name} coverUrl={p.coverUrl} subtitle={`By ${p.owner.displayName ?? 'someone'} • ${plural(p.trackCount, 'song')}`} />)}</div>
              </Section>
            )}
          </>
        )}
      </div>
    </>
  );
}
