import { Link } from 'react-router';
import { useLikedTracks } from '../api/hooks';
import { useAuth } from '../auth/AuthProvider';
import { useScrolled } from '../components/AppShell';
import { HeartFilledIcon, PauseIcon, PlayIcon } from '../components/Icons';
import { TopBar } from '../components/TopBar';
import { TrackList } from '../components/TrackList';
import { plural, relativeDate } from '../lib/format';
import { usePlayer, usePlayerActions } from '../player/PlayerProvider';
import type { QueueContext } from '../player/queue';
import { PageSkeleton } from './states';

const CONTEXT: QueueContext = { source: 'LIBRARY', sourceId: null, label: 'Liked Songs', href: '/collection/tracks' };

export function LikedPage() {
  const liked = useLikedTracks();
  const { profile } = useAuth();
  const scrolled = useScrolled(260);
  const { queue, isPlaying } = usePlayer();
  const player = usePlayerActions();
  if (liked.isPending) return <PageSkeleton />;
  const items = (liked.data ?? []).filter((l) => l.track);
  const tracks = items.map((l) => l.track);
  const active = queue.context?.label === CONTEXT.label && queue.context.source === 'LIBRARY';

  return (
    <div style={{ ['--hue' as string]: 262 }}>
      <TopBar solid={scrolled}>{scrolled && <strong style={{ fontFamily: 'var(--font-display)', fontSize: 20 }}>Liked Songs</strong>}</TopBar>
      <header className="hero">
        <div className="art liked-art hero-art"><HeartFilledIcon /></div>
        <div className="hero-body">
          <div className="eyebrow">Playlist</div>
          <h1 className="hero-title">Liked Songs</h1>
          <div className="hero-meta"><strong>{profile?.displayName}</strong><span className="dot">{plural(items.length, 'song')}</span></div>
        </div>
      </header>
      <div className="hero-tint">
        <div className="action-row">
          <button type="button" className="play-fab" disabled={tracks.length === 0} aria-label={active && isPlaying ? 'Pause' : 'Play Liked Songs'}
                  onClick={() => (active ? player.togglePlay() : player.playTracks(tracks, 0, CONTEXT))}>
            {active && isPlaying ? <PauseIcon /> : <PlayIcon />}
          </button>
        </div>
        <div className="page">
          {items.length === 0 ? (
            <div className="empty">
              <h2>Songs you like will appear here</h2>
              <p>Save songs by tapping the heart icon.</p>
              <Link className="btn btn-ghost" to="/search">Find songs</Link>
            </div>
          ) : (
            <TrackList items={items.map((l) => ({ track: l.track, extra: <span className="faint">{relativeDate(l.likedAt)}</span> }))}
                       context={CONTEXT} extraHeader="Date added" />
          )}
        </div>
      </div>
    </div>
  );
}
