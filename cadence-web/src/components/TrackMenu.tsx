import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { api } from '../api/endpoints';
import { keys, useMyPlaylists } from '../api/hooks';
import type { TrackSummary } from '../api/types';
import { useAuth } from '../auth/AuthProvider';
import { usePlayerActions } from '../player/PlayerProvider';
import { MoreIcon } from './Icons';
import { useToast } from './Toasts';

interface Props {
  track: TrackSummary;
  onRemove?: () => void;
}

/** The "…" menu of a track row: queue, add to playlist, go to artist/album, remove from this playlist. */
export function TrackMenu({ track, onRemove }: Props) {
  const [open, setOpen] = useState(false);
  const [sub, setSub] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();
  const player = usePlayerActions();
  const toast = useToast();
  const client = useQueryClient();
  const { profile } = useAuth();
  const playlists = useMyPlaylists();

  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false);
    };
    const esc = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', esc);
    return () => {
      document.removeEventListener('mousedown', close);
      document.removeEventListener('keydown', esc);
    };
  }, [open]);

  const addTo = async (playlistId: string, name: string) => {
    setOpen(false);
    try {
      await api.addToPlaylist(playlistId, [track.id]);
      await client.invalidateQueries({ queryKey: keys.playlist(playlistId) });
      await client.invalidateQueries({ queryKey: keys.playlists });
      toast(`Added to ${name}`);
    } catch {
      toast(`Could not add to ${name}`, true);
    }
  };
  const primary = track.artists[0];
  const own = (playlists.data ?? []).filter((p) => p.ownerId === profile?.id);

  return (
    <div ref={ref} style={{ position: 'relative' }} onClick={(e) => e.stopPropagation()}>
      <button type="button" className="icon-btn" aria-label={`More options for ${track.title}`} aria-haspopup="menu"
              aria-expanded={open} onClick={() => { setOpen((o) => !o); setSub(false); }}>
        <MoreIcon />
      </button>
      {open && (
        <div className="menu" role="menu">
          <button role="menuitem" onClick={() => { player.enqueue(track); setOpen(false); toast('Added to queue'); }}>Add to queue</button>
          <button role="menuitem" aria-haspopup="menu" onClick={() => setSub((s) => !s)}>Add to playlist {sub ? '▾' : '▸'}</button>
          {sub && (
            <div style={{ maxHeight: 220, overflow: 'auto', paddingLeft: 8 }}>
              {own.length === 0 && <div className="menu-label">No playlists yet</div>}
              {own.map((p) => (
                <button key={p.id} role="menuitem" onClick={() => addTo(p.id, p.name)}>{p.name}</button>
              ))}
            </div>
          )}
          {onRemove && <button role="menuitem" onClick={() => { setOpen(false); onRemove(); }}>Remove from this playlist</button>}
          <hr />
          {primary && <button role="menuitem" onClick={() => navigate(`/artist/${primary.id}`)}>Go to artist</button>}
          {track.album && <button role="menuitem" onClick={() => navigate(`/album/${track.album!.id}`)}>Go to album</button>}
        </div>
      )}
    </div>
  );
}
