import { useState, type DragEvent as ReactDragEvent, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router';
import { ApiError } from '../api/client';
import { api } from '../api/endpoints';
import { keys } from '../api/hooks';
import type { PlaylistDetail, TrackSummary, Visibility } from '../api/types';
import { useAuth } from '../auth/AuthProvider';
import { useScrolled } from '../components/AppShell';
import { Artwork } from '../components/Artwork';
import { MoreIcon, PauseIcon, PlayIcon } from '../components/Icons';
import { useToast } from '../components/Toasts';
import { TopBar } from '../components/TopBar';
import { TrackList } from '../components/TrackList';
import { hueOf } from '../lib/color';
import { longDuration, plural, relativeDate } from '../lib/format';
import { usePlayer, usePlayerActions } from '../player/PlayerProvider';
import type { QueueContext } from '../player/queue';
import { LoadError, PageSkeleton } from './states';

function EditDialog({ playlist, onClose }: { playlist: PlaylistDetail; onClose: () => void }) {
  const client = useQueryClient();
  const toast = useToast();
  const [name, setName] = useState(playlist.name);
  const [description, setDescription] = useState(playlist.description ?? '');
  const [visibility, setVisibility] = useState<Visibility>(playlist.visibility);
  const [error, setError] = useState<string | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    try {
      await api.updatePlaylist(playlist.id, playlist.version, { name: name.trim(), description, visibility });
      await client.invalidateQueries({ queryKey: keys.playlist(playlist.id) });
      await client.invalidateQueries({ queryKey: keys.playlists });
      toast('Playlist updated');
      onClose();
    } catch (err) {
      setError(err instanceof ApiError && err.status === 412
        ? 'This playlist changed somewhere else. Close and try again.' : 'Could not save the playlist.');
    }
  };
  return (
    <div className="dialog-backdrop" onMouseDown={onClose}>
      <form className="dialog" onMouseDown={(e) => e.stopPropagation()} onSubmit={submit} aria-label="Edit details">
        <h2>Edit details</h2>
        {error && <div className="form-error" role="alert">{error}</div>}
        <div className="field">
          <label htmlFor="pl-name">Name</label>
          <input id="pl-name" className="input" value={name} maxLength={100} onChange={(e) => setName(e.target.value)} required autoFocus />
        </div>
        <div className="field">
          <label htmlFor="pl-desc">Description</label>
          <textarea id="pl-desc" className="input" rows={3} maxLength={300} value={description} onChange={(e) => setDescription(e.target.value)}
                    placeholder="Add an optional description" />
        </div>
        <label className="check">
          <input type="checkbox" checked={visibility === 'PUBLIC'} onChange={(e) => setVisibility(e.target.checked ? 'PUBLIC' : 'PRIVATE')} />
          Public: anyone can find it in search
        </label>
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <button type="button" className="btn btn-quiet" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={!name.trim()}>Save</button>
        </div>
      </form>
    </div>
  );
}

export function PlaylistPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const client = useQueryClient();
  const toast = useToast();
  const scrolled = useScrolled(260);
  const { profile } = useAuth();
  const { queue, isPlaying } = usePlayer();
  const player = usePlayerActions();
  const playlist = useQuery({ queryKey: keys.playlist(id), queryFn: () => api.playlist(id) });
  const [editing, setEditing] = useState(false);
  const [menu, setMenu] = useState(false);
  const [dragFrom, setDragFrom] = useState<number | null>(null);
  const [dragOver, setDragOver] = useState<number | null>(null);

  if (playlist.isPending) return <PageSkeleton />;
  if (playlist.isError) return <LoadError what="playlist" error={playlist.error} />;
  const p = playlist.data;
  const owner = p.ownerId === profile?.id;
  const items = p.tracks.items.filter((i) => i.track);
  const playable = items.filter((i) => i.playable).map((i) => i.track!);
  const context: QueueContext = { source: 'PLAYLIST', sourceId: p.id, label: p.name, href: `/playlist/${p.id}` };
  const active = queue.context?.source === 'PLAYLIST' && queue.context.sourceId === p.id;
  const total = items.reduce((sum, i) => sum + (i.track?.durationMs ?? 0), 0);

  const refresh = async () => {
    await client.invalidateQueries({ queryKey: keys.playlist(p.id) });
    await client.invalidateQueries({ queryKey: keys.playlists });
  };
  const remove = async (track: TrackSummary) => {
    try {
      await api.removeFromPlaylist(p.id, [track.id]);
      await refresh();
      toast(`Removed from ${p.name}`);
    } catch {
      toast('Could not remove the track', true);
    }
  };
  const drop = async (to: number) => {
    const from = dragFrom;
    setDragFrom(null);
    setDragOver(null);
    if (from === null || from === to) return;
    // the moved track lands at index `to`: after items[to] when moving down, after items[to - 1] when moving up
    const after = to === 0 ? null : to > from ? items[to].trackId : items[to - 1].trackId;
    try {
      await api.reorderPlaylist(p.id, items[from].trackId, after);
      await refresh();
    } catch {
      toast('Could not reorder', true);
    }
  };
  const deletePlaylist = async () => {
    setMenu(false);
    if (!window.confirm(`Delete “${p.name}”? This can’t be undone.`)) return;
    await api.deletePlaylist(p.id);
    await client.invalidateQueries({ queryKey: keys.playlists });
    navigate('/');
  };

  return (
    <div style={{ ['--hue' as string]: hueOf(p.id) }}>
      <TopBar solid={scrolled}>{scrolled && <strong style={{ fontFamily: 'var(--font-display)', fontSize: 20 }}>{p.name}</strong>}</TopBar>
      <header className="hero">
        <Artwork seed={p.id} title={p.name} src={p.coverUrl} className="hero-art" />
        <div className="hero-body">
          <div className="eyebrow">{p.visibility === 'PUBLIC' ? 'Public playlist' : 'Private playlist'}</div>
          <h1 className={`hero-title${p.name.length > 22 ? ' long' : ''}`} style={owner ? { cursor: 'pointer' } : undefined}
              onClick={() => owner && setEditing(true)}>{p.name}</h1>
          {p.description && <p className="muted" style={{ margin: '-6px 0 12px' }}>{p.description}</p>}
          <div className="hero-meta">
            <strong>{owner ? profile?.displayName : p.ownerName ?? 'Cadence listener'}</strong>
            <span className="dot">{plural(p.trackCount, 'song')}{total > 0 ? `, ${longDuration(total)}` : ''}</span>
          </div>
        </div>
      </header>
      <div className="hero-tint">
        <div className="action-row">
          <button type="button" className="play-fab" disabled={playable.length === 0}
                  aria-label={active && isPlaying ? 'Pause' : `Play ${p.name}`}
                  onClick={() => (active ? player.togglePlay() : player.playTracks(playable, 0, context))}>
            {active && isPlaying ? <PauseIcon /> : <PlayIcon />}
          </button>
          {owner && (
            <div style={{ position: 'relative' }}>
              <button type="button" className="icon-btn" aria-label="Playlist options" onClick={() => setMenu((m) => !m)}>
                <MoreIcon width={26} height={26} />
              </button>
              {menu && (
                <div className="menu" style={{ left: 0, right: 'auto' }} role="menu">
                  <button role="menuitem" onClick={() => { setMenu(false); setEditing(true); }}>Edit details</button>
                  <button role="menuitem" onClick={deletePlaylist}>Delete playlist</button>
                </div>
              )}
            </div>
          )}
        </div>
        <div className="page">
          {items.length === 0 ? (
            <div className="empty">
              <h2>Let’s find something for your playlist</h2>
              <p>Use “Add to playlist” in any track’s menu.</p>
              <button type="button" className="btn btn-ghost" onClick={() => navigate('/search')}>Search for songs</button>
            </div>
          ) : (
            <TrackList
              items={items.map((i) => ({ track: i.track!, playable: i.playable, extra: <span className="faint">{relativeDate(i.addedAt)}</span> }))}
              context={context}
              extraHeader="Date added"
              onRemove={owner ? remove : undefined}
              rowProps={owner ? (index) => ({
                draggable: true,
                onDragStart: () => setDragFrom(index),
                onDragOver: (e: ReactDragEvent) => { e.preventDefault(); setDragOver(index); },
                onDrop: () => void drop(index),
                onDragEnd: () => { setDragFrom(null); setDragOver(null); },
                style: dragOver === index && dragFrom !== null && dragFrom !== index
                  ? { boxShadow: `inset 0 ${index > dragFrom ? -2 : 2}px 0 var(--accent)` } : undefined,
              }) : undefined}
            />
          )}
        </div>
      </div>
      {editing && <EditDialog playlist={p} onClose={() => setEditing(false)} />}
    </div>
  );
}
