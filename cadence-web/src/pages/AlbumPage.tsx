import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router';
import { api } from '../api/endpoints';
import { useSaveAlbumToggle, useSavedAlbums } from '../api/hooks';
import { useScrolled } from '../components/AppShell';
import { Artwork } from '../components/Artwork';
import { CheckIcon, PauseIcon, PlayIcon, PlusIcon } from '../components/Icons';
import { useToast } from '../components/Toasts';
import { TopBar } from '../components/TopBar';
import { TrackList } from '../components/TrackList';
import { hueOf } from '../lib/color';
import { longDuration, plural, year } from '../lib/format';
import { usePlayer, usePlayerActions } from '../player/PlayerProvider';
import type { QueueContext } from '../player/queue';
import { LoadError, PageSkeleton } from './states';

const TYPE_LABEL = { ALBUM: 'Album', EP: 'EP', SINGLE: 'Single' } as const;

export function AlbumPage() {
  const { id = '' } = useParams();
  const scrolled = useScrolled(260);
  const album = useQuery({ queryKey: ['album', id], queryFn: () => api.album(id) });
  const saved = (useSavedAlbums().data ?? []).some((s) => s.album?.id === id);
  const save = useSaveAlbumToggle();
  const [optimistic, setOptimistic] = useState<boolean | null>(null);
  const { queue, isPlaying } = usePlayer();
  const player = usePlayerActions();
  const toast = useToast();

  if (album.isPending) return <PageSkeleton />;
  if (album.isError) return <LoadError what="album" error={album.error} />;
  const a = album.data;
  const context: QueueContext = { source: 'ALBUM', sourceId: a.id, label: a.title, href: `/album/${a.id}` };
  const active = queue.context?.source === 'ALBUM' && queue.context.sourceId === a.id;
  const inLibrary = optimistic ?? saved;

  return (
    <div style={{ ['--hue' as string]: hueOf(a.id) }}>
      <TopBar solid={scrolled}>{scrolled && <strong style={{ fontFamily: 'var(--font-display)', fontSize: 20 }}>{a.title}</strong>}</TopBar>
      <header className="hero">
        <Artwork seed={a.id} title={a.title} src={a.coverUrl} className="hero-art" />
        <div className="hero-body">
          <div className="eyebrow">{TYPE_LABEL[a.type]}</div>
          <h1 className={`hero-title${a.title.length > 22 ? ' long' : ''}`}>{a.title}</h1>
          <div className="hero-meta">
            <Artwork seed={a.artist.id} title={a.artist.name} round size={24} />
            <Link className="link" to={`/artist/${a.artist.id}`}><strong>{a.artist.name}</strong></Link>
            <span className="dot">{year(a.releaseDate)}</span>
            <span className="dot">{plural(a.tracks.length, 'song')}, {longDuration(a.totalDurationMs)}</span>
          </div>
        </div>
      </header>
      <div className="hero-tint">
        <div className="action-row">
          <button type="button" className="play-fab" disabled={a.tracks.length === 0}
                  aria-label={active && isPlaying ? 'Pause' : `Play ${a.title}`}
                  onClick={() => (active ? player.togglePlay() : player.playTracks(a.tracks, 0, context))}>
            {active && isPlaying ? <PauseIcon /> : <PlayIcon />}
          </button>
          <button type="button" className={`icon-btn${inLibrary ? ' on' : ''}`} style={{ width: 40, height: 40, border: '1.5px solid currentColor' }}
                  aria-label={inLibrary ? 'Remove from Your Library' : 'Save to Your Library'} aria-pressed={inLibrary}
                  onClick={() => {
                    setOptimistic(!inLibrary);
                    save.mutate({ id: a.id, on: !inLibrary }, {
                      onSuccess: () => toast(!inLibrary ? 'Saved to Your Library' : 'Removed from Your Library'),
                      onError: () => toast('Could not update Your Library', true),
                      onSettled: () => setOptimistic(null),
                    });
                  }}>
            {inLibrary ? <CheckIcon /> : <PlusIcon />}
          </button>
        </div>
        <div className="page">
          {a.tracks.length === 0
            ? <div className="empty"><h2>No playable tracks yet</h2><p>This album’s tracks are still being processed.</p></div>
            : <TrackList items={a.tracks.map((t) => ({ track: t }))} context={context} showArt={false} showAlbum={false} albumNumbers />}
          <div className="faint" style={{ marginTop: 24, fontSize: 13 }}>
            {new Date(a.releaseDate).toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' })}
            {a.label && <div>℗ {year(a.releaseDate)} {a.label}</div>}
            {a.genres.length > 0 && <div>{a.genres.join(' · ')}</div>}
          </div>
        </div>
      </div>
    </div>
  );
}
