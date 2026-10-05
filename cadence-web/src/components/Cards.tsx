import type { ReactNode } from 'react';
import { useNavigate } from 'react-router';
import { api } from '../api/endpoints';
import type { TrackSummary } from '../api/types';
import { usePlayer, usePlayerActions } from '../player/PlayerProvider';
import type { QueueContext } from '../player/queue';
import { Artwork } from './Artwork';
import { PauseIcon, PlayIcon } from './Icons';
import { useToast } from './Toasts';

interface CardProps {
  seed: string;
  title: string;
  subtitle: ReactNode;
  href: string;
  imageUrl?: string | null;
  round?: boolean;
  /** Loads the tracks to play when the card's play button is pressed. */
  play?: () => Promise<{ tracks: TrackSummary[]; context: QueueContext }>;
  playingContext?: QueueContext;
}

export function Card({ seed, title, subtitle, href, imageUrl, round, play, playingContext }: CardProps) {
  const navigate = useNavigate();
  const { queue, isPlaying } = usePlayer();
  const player = usePlayerActions();
  const toast = useToast();
  const active = !!playingContext && queue.context?.source === playingContext.source
    && queue.context?.sourceId === playingContext.sourceId;
  return (
    <div className="card" onClick={() => navigate(href)} role="link" tabIndex={0}
         onKeyDown={(e) => e.key === 'Enter' && navigate(href)}>
      <div className="card-art-wrap">
        <Artwork seed={seed} title={title} src={imageUrl} round={round} />
        {play && (
          <button type="button" className={`play-fab sm${active ? ' visible' : ''}`}
                  aria-label={active && isPlaying ? `Pause ${title}` : `Play ${title}`}
                  onClick={async (e) => {
                    e.stopPropagation();
                    if (active) {
                      player.togglePlay();
                      return;
                    }
                    try {
                      const { tracks, context } = await play();
                      if (tracks.length === 0) toast('Nothing playable here yet');
                      else player.playTracks(tracks, 0, context);
                    } catch {
                      toast('Could not start playback', true);
                    }
                  }}>
            {active && isPlaying ? <PauseIcon /> : <PlayIcon />}
          </button>
        )}
      </div>
      <div className="card-title ellipsis">{title}</div>
      <div className="card-sub">{subtitle}</div>
    </div>
  );
}

export function AlbumCard({ id, title, coverUrl, subtitle }: { id: string; title: string; coverUrl?: string | null; subtitle: ReactNode }) {
  const context: QueueContext = { source: 'ALBUM', sourceId: id, label: title, href: `/album/${id}` };
  return (
    <Card seed={id} title={title} subtitle={subtitle} href={`/album/${id}`} imageUrl={coverUrl} playingContext={context}
          play={async () => ({ tracks: (await api.album(id)).tracks, context })} />
  );
}

export function ArtistCard({ id, name, imageUrl }: { id: string; name: string; imageUrl?: string | null }) {
  const context: QueueContext = { source: 'ARTIST', sourceId: id, label: name, href: `/artist/${id}` };
  return (
    <Card seed={id} title={name} subtitle="Artist" href={`/artist/${id}`} imageUrl={imageUrl} round playingContext={context}
          play={async () => ({ tracks: (await api.artist(id)).topTracks, context })} />
  );
}

export function PlaylistCard({ id, name, subtitle, coverUrl }: { id: string; name: string; subtitle: ReactNode; coverUrl?: string | null }) {
  const context: QueueContext = { source: 'PLAYLIST', sourceId: id, label: name, href: `/playlist/${id}` };
  return (
    <Card seed={id} title={name} subtitle={subtitle} href={`/playlist/${id}`} imageUrl={coverUrl} playingContext={context}
          play={async () => ({
            tracks: (await api.playlist(id)).tracks.items.filter((i) => i.playable && i.track).map((i) => i.track!),
            context,
          })} />
  );
}

export function Section({ title, href, children }: { title: string; href?: string; children: ReactNode }) {
  const navigate = useNavigate();
  return (
    <section className="section">
      <div className="section-head">
        <h2 className="section-title">{href ? <a href={href} onClick={(e) => { e.preventDefault(); navigate(href); }}>{title}</a> : title}</h2>
        {href && <a className="see-all" href={href} onClick={(e) => { e.preventDefault(); navigate(href); }}>Show all</a>}
      </div>
      {children}
    </section>
  );
}
