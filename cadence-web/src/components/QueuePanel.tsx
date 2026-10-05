import { Link } from 'react-router';
import { artistNames } from '../lib/format';
import { usePlayer, usePlayerActions } from '../player/PlayerProvider';
import { upcoming } from '../player/queue';
import { Artwork } from './Artwork';
import { CloseIcon } from './Icons';

export function QueuePanel({ onClose }: { onClose: () => void }) {
  const { queue, current } = usePlayer();
  const player = usePlayerActions();
  const next = upcoming(queue);

  return (
    <aside className="panel queue" aria-label="Queue">
      <div className="queue-head">
        <h2>Queue</h2>
        <button type="button" className="icon-btn" aria-label="Close queue" onClick={onClose}><CloseIcon /></button>
      </div>
      <div className="scroll" style={{ flex: 1, minHeight: 0 }}>
        {!current && <div className="queue-empty">Your queue is empty. Play something, or add tracks with “Add to queue”.</div>}
        {current && (
          <>
            <div className="queue-label">Now playing</div>
            <div className="q-item now">
              <Artwork seed={current.album?.id ?? current.id} title={current.album?.title ?? current.title} src={current.album?.coverUrl} />
              <div style={{ minWidth: 0 }}>
                <div className="t ellipsis">{current.title}</div>
                <div className="s ellipsis">{artistNames(current.artists)}</div>
              </div>
            </div>
          </>
        )}
        {next.upNext.length > 0 && <div className="queue-label">Next in queue</div>}
        {next.upNext.map((t, i) => (
          <div key={`up-${t.id}-${i}`} className="q-item">
            <Artwork seed={t.album?.id ?? t.id} title={t.album?.title ?? t.title} src={t.album?.coverUrl} />
            <div style={{ minWidth: 0 }}>
              <div className="t ellipsis">{t.title}</div>
              <div className="s ellipsis">{artistNames(t.artists)}</div>
            </div>
            <button type="button" className="icon-btn" aria-label={`Remove ${t.title} from queue`} onClick={() => player.removeFromUpNext(i)}>
              <CloseIcon />
            </button>
          </div>
        ))}
        {next.context.length > 0 && (
          <div className="queue-label">
            Next from: {queue.context?.href ? <Link to={queue.context.href}>{queue.context.label}</Link> : queue.context?.label}
          </div>
        )}
        {next.context.map(({ track: t, position }) => (
          <div key={`ctx-${t.id}-${position}`} className="q-item" role="button" tabIndex={0}
               onClick={() => player.jumpTo(position)} onKeyDown={(e) => e.key === 'Enter' && player.jumpTo(position)}>
            <Artwork seed={t.album?.id ?? t.id} title={t.album?.title ?? t.title} src={t.album?.coverUrl} />
            <div style={{ minWidth: 0 }}>
              <div className="t ellipsis">{t.title}</div>
              <div className="s ellipsis">{artistNames(t.artists)}</div>
            </div>
          </div>
        ))}
      </div>
    </aside>
  );
}
