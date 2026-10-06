import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { clock } from '../lib/format';
import { usePlayer, usePlayerActions, usePlayerProgress } from '../player/PlayerProvider';
import { Artwork } from './Artwork';
import {
  MuteIcon, NextIcon, PauseIcon, PlayIcon, PrevIcon, QueueIcon, RepeatIcon, RepeatOneIcon, ShuffleIcon, VolumeIcon, VolumeLowIcon,
} from './Icons';
import { LikeButton } from './LikeButton';
import { Slider } from './Slider';
import { Artists } from './TrackList';

function Progress() {
  const { position, duration, buffered } = usePlayerProgress();
  const { current } = usePlayer();
  const player = usePlayerActions();
  const [preview, setPreview] = useState<number | null>(null);
  const total = duration || (current?.durationMs ?? 0) / 1000;
  return (
    <div className="progress tnum">
      <span className="t-left">{clock(preview ?? position)}</span>
      <Slider value={position} max={total} buffered={buffered} label="Seek" valueText={`${clock(position)} of ${clock(total)}`}
              onPreview={setPreview} onCommit={(v) => player.seek(v)} />
      <span>{clock(total)}</span>
    </div>
  );
}

/** Free plan ad slot placeholder (D96): there is no ad content, just a short break before the track. */
function AdBreak({ until }: { until: number }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(timer);
  }, []);
  const seconds = Math.max(0, Math.ceil((until - now) / 1000));
  return (
    <div className="ad-break ellipsis" role="status">
      Ad break · your track starts in {seconds} s · Premium has no ads
    </div>
  );
}

export function PlayerBar({ queueOpen, onToggleQueue }: { queueOpen: boolean; onToggleQueue: () => void }) {
  const { current, isPlaying, isLoading, queue, volume, muted, error, adBreakUntil } = usePlayer();
  const player = usePlayerActions();
  const repeatLabel = queue.repeat === 'off' ? 'Enable repeat' : queue.repeat === 'all' ? 'Enable repeat one' : 'Disable repeat';
  const VolumeGlyph = muted || volume === 0 ? MuteIcon : volume < 0.5 ? VolumeLowIcon : VolumeIcon;

  return (
    <footer className="player" aria-label="Player">
      <div className="now">
        {current ? (
          <>
            <Artwork seed={current.album?.id ?? current.id} title={current.album?.title ?? current.title} src={current.album?.coverUrl} />
            <div className="now-text">
              <div className="now-title ellipsis">
                {current.album ? <Link to={`/album/${current.album.id}`}>{current.title}</Link> : current.title}
              </div>
              <div className="now-artists"><Artists track={current} /></div>
              {error && <div className="field-error ellipsis" role="alert">{error}</div>}
              {adBreakUntil && <AdBreak until={adBreakUntil} />}
            </div>
            <LikeButton trackId={current.id} />
          </>
        ) : <span className="faint" style={{ paddingLeft: 12, fontSize: 13 }}>Pick something to play</span>}
      </div>

      <div className="controls">
        <div className="control-row">
          <button type="button" className={`icon-btn${queue.shuffle ? ' on' : ''}`} aria-label={queue.shuffle ? 'Disable shuffle' : 'Enable shuffle'}
                  aria-pressed={queue.shuffle} onClick={player.toggleShuffle} disabled={!current}>
            <ShuffleIcon />
          </button>
          <button type="button" className="icon-btn" aria-label="Previous" onClick={player.previous} disabled={!current}><PrevIcon /></button>
          <button type="button" className="play-btn" aria-label={isPlaying ? 'Pause' : 'Play'} onClick={player.togglePlay} disabled={!current}>
            {isLoading && isPlaying ? <span className="spinner" /> : isPlaying ? <PauseIcon /> : <PlayIcon />}
          </button>
          <button type="button" className="icon-btn" aria-label="Next" onClick={player.next} disabled={!current}><NextIcon /></button>
          <button type="button" className={`icon-btn${queue.repeat !== 'off' ? ' on' : ''}`} aria-label={repeatLabel}
                  onClick={player.cycleRepeat} disabled={!current}>
            {queue.repeat === 'one' ? <RepeatOneIcon /> : <RepeatIcon />}
          </button>
        </div>
        <Progress />
      </div>

      <div className="extras">
        <button type="button" className={`icon-btn${queueOpen ? ' on' : ''}`} aria-label="Queue" aria-pressed={queueOpen} onClick={onToggleQueue}>
          <QueueIcon />
        </button>
        <div className="volume">
          <button type="button" className="icon-btn" aria-label={muted ? 'Unmute' : 'Mute'} onClick={player.toggleMute}><VolumeGlyph /></button>
          <Slider value={muted ? 0 : volume} max={1} step={0.05} label="Volume" valueText={`${Math.round((muted ? 0 : volume) * 100)}%`}
                  onPreview={(v) => v !== null && player.setVolume(v)} onCommit={player.setVolume} />
        </div>
      </div>
    </footer>
  );
}
