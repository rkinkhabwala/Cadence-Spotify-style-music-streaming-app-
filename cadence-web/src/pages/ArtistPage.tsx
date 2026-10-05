import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router';
import { api } from '../api/endpoints';
import { useFollowToggle, useFollowedArtists } from '../api/hooks';
import { useScrolled } from '../components/AppShell';
import { Artwork } from '../components/Artwork';
import { AlbumCard, Section } from '../components/Cards';
import { PauseIcon, PlayIcon, ShuffleIcon, VerifiedIcon } from '../components/Icons';
import { useToast } from '../components/Toasts';
import { TopBar } from '../components/TopBar';
import { TrackList } from '../components/TrackList';
import { hueOf } from '../lib/color';
import { plural, year } from '../lib/format';
import { usePlayer, usePlayerActions } from '../player/PlayerProvider';
import type { QueueContext } from '../player/queue';
import { LoadError, PageSkeleton } from './states';

export function ArtistPage() {
  const { id = '' } = useParams();
  const scrolled = useScrolled(260);
  const artist = useQuery({ queryKey: ['artist', id], queryFn: () => api.artist(id) });
  const albums = useQuery({ queryKey: ['artist', id, 'albums'], queryFn: () => api.artistAlbums(id) });
  const followed = (useFollowedArtists().data ?? []).some((f) => f.artist.id === id);
  const follow = useFollowToggle();
  const [optimistic, setOptimistic] = useState<boolean | null>(null);
  const { queue, isPlaying } = usePlayer();
  const player = usePlayerActions();
  const toast = useToast();
  const [showAll, setShowAll] = useState(false);

  if (artist.isPending) return <PageSkeleton />;
  if (artist.isError) return <LoadError what="artist" error={artist.error} />;
  const a = artist.data;
  const context: QueueContext = { source: 'ARTIST', sourceId: a.id, label: a.name, href: `/artist/${a.id}` };
  const active = queue.context?.source === 'ARTIST' && queue.context.sourceId === a.id;
  const following = optimistic ?? followed;
  const playable = a.topTracks.filter((t) => t.status === 'READY');

  return (
    <div style={{ ['--hue' as string]: hueOf(a.id) }}>
      <TopBar solid={scrolled}>{scrolled && <strong style={{ fontFamily: 'var(--font-display)', fontSize: 20 }}>{a.name}</strong>}</TopBar>
      <header className="hero">
        <Artwork seed={a.id} title={a.name} src={a.imageUrl} round className="hero-art" />
        <div className="hero-body">
          {a.verified && <div className="verified"><VerifiedIcon /> Verified artist</div>}
          <h1 className={`hero-title${a.name.length > 22 ? ' long' : ''}`}>{a.name}</h1>
          <div className="hero-meta">{a.bio ?? plural(a.topTracks.length, 'popular track')}</div>
        </div>
      </header>
      <div className="hero-tint">
        <div className="action-row">
          <button type="button" className="play-fab" disabled={playable.length === 0}
                  aria-label={active && isPlaying ? 'Pause' : `Play ${a.name}`}
                  onClick={() => (active ? player.togglePlay() : player.playTracks(playable, 0, context))}>
            {active && isPlaying ? <PauseIcon /> : <PlayIcon />}
          </button>
          <button type="button" className="icon-btn" aria-label="Shuffle play" disabled={playable.length === 0} onClick={() => {
            if (!queue.shuffle) player.toggleShuffle();
            player.playTracks(playable, Math.floor(Math.random() * playable.length), context);
          }}><ShuffleIcon width={24} height={24} /></button>
          <button type="button" className="btn btn-ghost btn-sm" aria-pressed={following} onClick={() => {
            setOptimistic(!following);
            follow.mutate({ id: a.id, on: !following }, {
              onError: () => toast('Could not update follow', true),
              onSettled: () => setOptimistic(null),
            });
          }}>{following ? 'Following' : 'Follow'}</button>
        </div>
        <div className="page">
          <Section title="Popular">
            {playable.length === 0
              ? <p className="muted">No tracks are playable yet.</p>
              : <TrackList items={(showAll ? a.topTracks : a.topTracks.slice(0, 5)).map((t) => ({
                  track: t, extra: <span className="tl-plays tnum">{t.playCount.toLocaleString()} plays</span>,
                }))} context={context} extraHeader="Plays" />}
            {a.topTracks.length > 5 && (
              <button type="button" className="btn btn-quiet" style={{ marginTop: 8 }} onClick={() => setShowAll((s) => !s)}>
                {showAll ? 'Show less' : 'See more'}
              </button>
            )}
          </Section>
          {albums.data && albums.data.items.length > 0 && (
            <Section title="Discography">
              <div className="grid-cards">
                {albums.data.items.map((al) => (
                  <AlbumCard key={al.id} id={al.id} title={al.title} coverUrl={al.coverUrl}
                             subtitle={`${year(al.releaseDate)} • ${al.type === 'ALBUM' ? 'Album' : al.type === 'EP' ? 'EP' : 'Single'}`} />
                ))}
              </div>
            </Section>
          )}
        </div>
      </div>
    </div>
  );
}
