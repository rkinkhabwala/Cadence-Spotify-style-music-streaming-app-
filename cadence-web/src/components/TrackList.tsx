import { Fragment, type ReactNode } from 'react';
import { Link } from 'react-router';
import type { TrackSummary } from '../api/types';
import { duration } from '../lib/format';
import { usePlayer, usePlayerActions } from '../player/PlayerProvider';
import type { QueueContext } from '../player/queue';
import { Artwork } from './Artwork';
import { ClockIcon, PauseIcon, PlayIcon } from './Icons';
import { LikeButton } from './LikeButton';
import { TrackMenu } from './TrackMenu';

export interface TrackListItem {
  track: TrackSummary;
  playable?: boolean;
  /** Replaces the album column (e.g. "date added", play counts). */
  extra?: ReactNode;
}

interface Props {
  items: TrackListItem[];
  context: QueueContext;
  showArt?: boolean;
  showAlbum?: boolean;
  /** Numbers rows by trackNumber (album pages) instead of position. */
  albumNumbers?: boolean;
  extraHeader?: string;
  onRemove?: (track: TrackSummary) => void;
  rowProps?: (index: number) => Record<string, unknown>;
}

export function Equalizer({ paused }: { paused: boolean }) {
  return <span className={`eq${paused ? ' paused' : ''}`} aria-hidden="true"><i /><i /><i /></span>;
}

export function Artists({ track }: { track: TrackSummary }) {
  return (
    <span className="tl-artists ellipsis">
      {track.explicit && <span className="explicit" title="Explicit">E</span>}
      {track.artists.map((a, i) => (
        <Fragment key={a.id}>
          {i > 0 && ', '}
          <Link to={`/artist/${a.id}`} onClick={(e) => e.stopPropagation()}>{a.name}</Link>
        </Fragment>
      ))}
    </span>
  );
}

export function TrackList({ items, context, showArt = true, showAlbum = true, albumNumbers, extraHeader, onRemove, rowProps }: Props) {
  const { current, isPlaying, queue } = usePlayer();
  const player = usePlayerActions();
  const tracks = items.map((i) => i.track);
  const sameContext = queue.context?.source === context.source && queue.context?.sourceId === context.sourceId;

  return (
    <div className={`tracklist${showAlbum || extraHeader ? '' : ' no-album'}`} role="table" aria-label="Tracks">
      <div className="tl-head" role="row">
        <span style={{ textAlign: 'center' }}>#</span>
        <span>Title</span>
        {(showAlbum || extraHeader) && <span className="tl-album">{extraHeader ?? 'Album'}</span>}
        <span className="dur" aria-label="Duration"><ClockIcon /></span>
      </div>
      {items.map((item, index) => {
        const t = item.track;
        const playable = item.playable ?? t.status === 'READY';
        const isCurrent = current?.id === t.id && (sameContext || !queue.context);
        const start = () => {
          if (!playable) return;
          if (isCurrent) player.togglePlay();
          else player.playTracks(tracks, index, context);
        };
        return (
          <div key={`${t.id}-${index}`} role="row"
               className={`tl-row${isCurrent ? ' current' : ''}${playable ? '' : ' unplayable'}`}
               onDoubleClick={start} {...rowProps?.(index)}>
            <div className="tl-index">
              <span className="num tnum">{albumNumbers ? t.trackNumber : index + 1}</span>
              {isCurrent && <Equalizer paused={!isPlaying} />}
              <button type="button" aria-label={isCurrent && isPlaying ? `Pause ${t.title}` : `Play ${t.title}`}
                      onClick={start} disabled={!playable}>
                {isCurrent && isPlaying ? <PauseIcon /> : <PlayIcon />}
              </button>
            </div>
            <div className="tl-main">
              {showArt && <Artwork seed={t.album?.id ?? t.id} title={t.album?.title ?? t.title} src={t.album?.coverUrl} />}
              <div style={{ minWidth: 0 }}>
                <div className="tl-title ellipsis">{t.title}</div>
                <Artists track={t} />
              </div>
            </div>
            {(showAlbum || extraHeader) && (
              <div className="tl-album ellipsis">
                {item.extra ?? (t.album ? <Link className="link" to={`/album/${t.album.id}`}>{t.album.title}</Link> : null)}
              </div>
            )}
            <div className="tl-end">
              {playable && <LikeButton trackId={t.id} />}
              <span className="tl-dur tnum">{duration(t.durationMs)}</span>
              <TrackMenu track={t} onRemove={onRemove ? () => onRemove(t) : undefined} />
            </div>
          </div>
        );
      })}
    </div>
  );
}
