import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { api } from '../api/endpoints';
import { keys, useMyPlaylists } from '../api/hooks';
import type { Shelf, TrackSummary } from '../api/types';
import { useAuth } from '../auth/AuthProvider';
import { Artwork } from '../components/Artwork';
import { AlbumCard, Section } from '../components/Cards';
import { PauseIcon, PlayIcon } from '../components/Icons';
import { TopBar } from '../components/TopBar';
import { useScrolled } from '../components/AppShell';
import { artistNames, greeting, year } from '../lib/format';
import { usePlayer, usePlayerActions } from '../player/PlayerProvider';
import type { QueueContext } from '../player/queue';

function TrackCard({ track, tracks, index, context }: { track: TrackSummary; tracks: TrackSummary[]; index: number; context: QueueContext }) {
  const { current, isPlaying } = usePlayer();
  const player = usePlayerActions();
  const isCurrent = current?.id === track.id;
  return (
    <div className="card" role="button" tabIndex={0}
         onClick={() => (isCurrent ? player.togglePlay() : player.playTracks(tracks, index, context))}
         onKeyDown={(e) => e.key === 'Enter' && player.playTracks(tracks, index, context)}>
      <div className="card-art-wrap">
        <Artwork seed={track.album?.id ?? track.id} title={track.album?.title ?? track.title} src={track.album?.coverUrl} />
        <span className={`play-fab sm${isCurrent ? ' visible' : ''}`} aria-hidden="true">
          {isCurrent && isPlaying ? <PauseIcon /> : <PlayIcon />}
        </span>
      </div>
      <div className="card-title ellipsis" style={isCurrent ? { color: 'var(--accent)' } : undefined}>{track.title}</div>
      <div className="card-sub">{artistNames(track.artists)}</div>
    </div>
  );
}

function ShelfRow({ shelf }: { shelf: Shelf }) {
  const tracks = shelf.items.flatMap((i) => (i.type === 'track' ? [i.track] : []));
  const context: QueueContext = {
    source: shelf.id === 'popular' ? 'OTHER' : 'LIBRARY', sourceId: null, label: shelf.title, href: '/',
    ...(shelf.recommendationId ? {
      recommendationId: shelf.recommendationId,
      positions: Object.fromEntries(shelf.items.flatMap((i) => (i.type === 'track' && i.position != null ? [[i.track.id, i.position]] : []))),
    } : {}),
  };
  return (
    <Section title={shelf.title}>
      <div className="shelf">
        {shelf.items.map((item) => item.type === 'track'
          ? <TrackCard key={item.track.id} track={item.track} tracks={tracks} index={tracks.indexOf(item.track)} context={context} />
          : <AlbumCard key={item.album.id} id={item.album.id} title={item.album.title} coverUrl={item.album.coverUrl}
                       subtitle={`${year(item.album.releaseDate)} • ${item.album.artist.name}`} />)}
      </div>
    </Section>
  );
}

export function HomePage() {
  const { profile } = useAuth();
  const scrolled = useScrolled(40);
  const home = useQuery({ queryKey: keys.home, queryFn: api.home, staleTime: 30_000 });
  const playlists = useMyPlaylists().data ?? [];
  const recent = home.data?.shelves.find((s) => s.id === 'recently-played');
  const quick = [
    ...playlists.slice(0, 3).map((p) => ({ id: p.id, title: p.name, href: `/playlist/${p.id}`, seed: p.id, cover: p.coverUrl })),
    ...(recent?.items ?? []).flatMap((i) => i.type === 'track' && i.track.album
      ? [{ id: i.track.album.id, title: i.track.album.title, href: `/album/${i.track.album.id}`, seed: i.track.album.id, cover: i.track.album.coverUrl }]
      : []),
  ].filter((v, i, all) => all.findIndex((x) => x.id === v.id) === i).slice(0, 6);

  return (
    <div className="hero-tint" style={{ ['--hue' as string]: 18 }}>
      <TopBar solid={scrolled} />
      <div className="page">
        <h1 className="greeting">{greeting()}{profile ? `, ${profile.displayName.split(' ')[0]}` : ''}</h1>
        {quick.length > 0 && (
          <div className="quick-grid">
            <Link className="quick" to="/collection/tracks">
              <div className="art liked-art" style={{ width: 64, borderRadius: 0 }}><svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 20.3s-7.5-4.6-9.2-9.4C1.6 7.4 3.8 4 7.3 4c2 0 3.6 1.1 4.7 2.7C13.1 5.1 14.7 4 16.7 4c3.5 0 5.7 3.4 4.5 6.9-1.7 4.8-9.2 9.4-9.2 9.4Z" /></svg></div>
              <span>Liked Songs</span>
            </Link>
            {quick.map((q) => (
              <Link key={q.id} className="quick" to={q.href}>
                <Artwork seed={q.seed} title={q.title} src={q.cover} />
                <span className="ellipsis">{q.title}</span>
              </Link>
            ))}
          </div>
        )}
        {home.isPending && <div className="section"><div className="skeleton" style={{ height: 240 }} /></div>}
        {home.isError && <div className="empty"><h2>Couldn’t load your home</h2><p>Check that the API is running, then refresh.</p></div>}
        {home.data?.shelves.map((shelf) => <ShelfRow key={shelf.id} shelf={shelf} />)}
        {home.data && home.data.shelves.length === 0 && (
          <div className="empty">
            <h2>Nothing here yet</h2>
            <p>Search for an artist or album and start listening. Your history and favourites will show up here.</p>
            <Link className="btn btn-primary" to="/search">Find something to play</Link>
          </div>
        )}
      </div>
    </div>
  );
}
