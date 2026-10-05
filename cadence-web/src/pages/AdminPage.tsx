import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Navigate } from 'react-router';
import { ApiError } from '../api/client';
import { api } from '../api/endpoints';
import type { AdminTrack, AlbumType, TrackStatus } from '../api/types';
import { useAuth } from '../auth/AuthProvider';
import { useScrolled } from '../components/AppShell';
import { Artwork } from '../components/Artwork';
import { UploadIcon } from '../components/Icons';
import { useToast } from '../components/Toasts';
import { TopBar } from '../components/TopBar';
import { duration, relativeDate } from '../lib/format';

const AUDIO = { mp3: 'audio/mpeg', flac: 'audio/flac', wav: 'audio/wav', m4a: 'audio/mp4' } as const;
type Ext = keyof typeof AUDIO;
const MAX_BYTES = 200 * 1024 * 1024;

interface Picked {
  id: string;
  name: string;
}

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    return error.problem.errors?.map((e) => `${e.field} ${e.message}`).join(', ') ?? error.message;
  }
  return error instanceof Error ? error.message : 'Something went wrong';
}

/** Search-backed picker for an existing artist or album (new entities are searchable within a second). */
function Picker({ kind, value, onPick, placeholder }: { kind: 'artist' | 'album'; value: Picked | null; onPick: (p: Picked | null) => void; placeholder: string }) {
  const [text, setText] = useState('');
  const [open, setOpen] = useState(false);
  const results = useQuery({
    queryKey: ['admin-picker', kind, text],
    queryFn: () => api.search(text, kind, 8),
    enabled: text.trim().length > 0,
  });
  const options: Picked[] = kind === 'artist'
    ? (results.data?.artists?.items ?? []).map((a) => ({ id: a.id, name: a.name }))
    : (results.data?.albums?.items ?? []).map((a) => ({ id: a.id, name: `${a.title} — ${a.artist.name}` }));
  if (value) {
    return (
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <Artwork seed={value.id} title={value.name} round={kind === 'artist'} size={36} />
        <strong className="ellipsis" style={{ flex: 1 }}>{value.name}</strong>
        <button type="button" className="btn btn-quiet btn-sm" onClick={() => onPick(null)}>Change</button>
      </div>
    );
  }
  return (
    <div className="picker">
      <input className="input" style={{ width: '100%' }} placeholder={placeholder} value={text}
             onChange={(e) => { setText(e.target.value); setOpen(true); }} onFocus={() => setOpen(true)}
             onBlur={() => setTimeout(() => setOpen(false), 150)} aria-label={placeholder} />
      {open && options.length > 0 && (
        <div className="picker-list">
          {options.map((o) => (
            <button key={o.id} type="button" onMouseDown={() => { onPick(o); setText(''); }}>
              <Artwork seed={o.id} title={o.name} round={kind === 'artist'} size={28} />{o.name}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function ArtistStep({ artist, onDone }: { artist: Picked | null; onDone: (p: Picked | null) => void }) {
  const [name, setName] = useState('');
  const [bio, setBio] = useState('');
  const [verified, setVerified] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const create = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      const created = await api.createArtist({ name: name.trim(), bio: bio.trim() || undefined, verified });
      onDone({ id: created.id, name: created.name });
      setName('');
      setBio('');
    } catch (err) {
      setError(errorText(err));
    }
  };
  return (
    <div className="box">
      <h3><span className={`step${artist ? ' done' : ''}`}>1</span> Artist</h3>
      <Picker kind="artist" value={artist} onPick={onDone} placeholder="Find an existing artist…" />
      {!artist && (
        <form onSubmit={create} style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <div className="faint" style={{ fontSize: 13 }}>…or create a new one</div>
          {error && <div className="form-error">{error}</div>}
          <input className="input" placeholder="Artist name" value={name} onChange={(e) => setName(e.target.value)} required aria-label="Artist name" />
          <textarea className="input" placeholder="Short bio (optional)" rows={2} value={bio} onChange={(e) => setBio(e.target.value)} aria-label="Bio" />
          <label className="check"><input type="checkbox" checked={verified} onChange={(e) => setVerified(e.target.checked)} /> Verified artist</label>
          <button type="submit" className="btn btn-ghost btn-sm" disabled={!name.trim()}>Create artist</button>
        </form>
      )}
    </div>
  );
}

function AlbumStep({ artist, album, onDone }: { artist: Picked | null; album: Picked | null; onDone: (p: Picked | null) => void }) {
  const [title, setTitle] = useState('');
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10));
  const [type, setType] = useState<AlbumType>('ALBUM');
  const [genres, setGenres] = useState('');
  const [label, setLabel] = useState('');
  const [error, setError] = useState<string | null>(null);
  const create = async (e: FormEvent) => {
    e.preventDefault();
    if (!artist) return;
    setError(null);
    try {
      const created = await api.createAlbum({
        title: title.trim(), artistId: artist.id, releaseDate: date, type, label: label.trim() || undefined,
        genres: genres.split(',').map((g) => g.trim()).filter(Boolean),
      });
      onDone({ id: created.id, name: `${created.title} — ${artist.name}` });
      setTitle('');
    } catch (err) {
      setError(errorText(err));
    }
  };
  return (
    <div className="box" style={artist ? undefined : { opacity: 0.5, pointerEvents: 'none' }}>
      <h3><span className={`step${album ? ' done' : ''}`}>2</span> Album</h3>
      <Picker kind="album" value={album} onPick={onDone} placeholder="Find an existing album…" />
      {!album && (
        <form onSubmit={create} style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <div className="faint" style={{ fontSize: 13 }}>…or create one{artist ? ` for ${artist.name}` : ''}</div>
          {error && <div className="form-error">{error}</div>}
          <input className="input" placeholder="Album title" value={title} onChange={(e) => setTitle(e.target.value)} required aria-label="Album title" />
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
            <input className="input" type="date" value={date} onChange={(e) => setDate(e.target.value)} required aria-label="Release date" />
            <select className="input" value={type} onChange={(e) => setType(e.target.value as AlbumType)} aria-label="Album type">
              <option value="ALBUM">Album</option><option value="EP">EP</option><option value="SINGLE">Single</option>
            </select>
          </div>
          <input className="input" placeholder="Genres, comma-separated" value={genres} onChange={(e) => setGenres(e.target.value)} aria-label="Genres" />
          <input className="input" placeholder="Label (optional)" value={label} onChange={(e) => setLabel(e.target.value)} aria-label="Label" />
          <button type="submit" className="btn btn-ghost btn-sm" disabled={!title.trim() || !artist}>Create album</button>
        </form>
      )}
    </div>
  );
}

/** PUT to the presigned URL with the exact signed headers, reporting progress (fetch can't report upload progress). */
function putWithProgress(url: string, file: File, headers: Record<string, string>, onProgress: (pct: number) => void): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', url);
    Object.entries(headers).filter(([k]) => k.toLowerCase() !== 'content-length').forEach(([k, v]) => xhr.setRequestHeader(k, v));
    xhr.upload.onprogress = (e) => e.lengthComputable && onProgress((e.loaded / e.total) * 100);
    xhr.onload = () => (xhr.status >= 200 && xhr.status < 300 ? resolve() : reject(new Error(`Storage rejected the upload (${xhr.status})`)));
    xhr.onerror = () => reject(new Error('Upload failed: is MinIO reachable from the browser?'));
    xhr.send(file);
  });
}

function TrackStep({ album, onUploaded }: { album: Picked | null; onUploaded: () => void }) {
  const [title, setTitle] = useState('');
  const [number, setNumber] = useState(1);
  const [explicit, setExplicit] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [over, setOver] = useState(false);
  const [progress, setProgress] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);
  const toast = useToast();

  const choose = (f: File | undefined) => {
    if (!f) return;
    const ext = f.name.split('.').pop()?.toLowerCase() as Ext | undefined;
    if (!ext || !(ext in AUDIO)) {
      setError('Choose an mp3, flac, wav or m4a file.');
      return;
    }
    if (f.size > MAX_BYTES) {
      setError('Audio files can be at most 200 MB.');
      return;
    }
    setError(null);
    setFile(f);
    if (!title) setTitle(f.name.replace(/\.[^.]+$/, '').replace(/[-_]+/g, ' ').replace(/^\d+\s*/, ''));
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!album || !file) return;
    setError(null);
    setProgress(0);
    try {
      const track = await api.createTrack({ title: title.trim(), albumId: album.id, trackNumber: number, explicit });
      const ext = file.name.split('.').pop()!.toLowerCase();
      const target = await api.uploadUrl(track.id, ext, file.size);
      await putWithProgress(target.uploadUrl, file, target.headers, setProgress);
      await api.uploadComplete(track.id);
      toast(`“${track.title}” uploaded. Transcoding…`);
      setFile(null);
      setTitle('');
      setNumber((n) => n + 1);
      onUploaded();
    } catch (err) {
      setError(errorText(err));
    } finally {
      setProgress(null);
    }
  };

  return (
    <form className="box" onSubmit={submit} style={album ? undefined : { opacity: 0.5, pointerEvents: 'none' }}>
      <h3><span className="step">3</span> Track & audio</h3>
      {error && <div className="form-error" role="alert">{error}</div>}
      <div className={`dropzone${over ? ' over' : ''}`} role="button" tabIndex={0}
           onClick={() => input.current?.click()} onKeyDown={(e) => e.key === 'Enter' && input.current?.click()}
           onDragOver={(e) => { e.preventDefault(); setOver(true); }} onDragLeave={() => setOver(false)}
           onDrop={(e) => { e.preventDefault(); setOver(false); choose(e.dataTransfer.files[0]); }}>
        <UploadIcon width={26} height={26} style={{ margin: '0 auto 6px' }} />
        {file ? <><strong>{file.name}</strong><div className="faint">{(file.size / 1024 / 1024).toFixed(1)} MB</div></>
          : <>Drop an audio file or <u>browse</u><div className="faint" style={{ fontSize: 12.5 }}>mp3 · flac · wav · m4a, up to 200 MB</div></>}
        <input ref={input} type="file" accept=".mp3,.flac,.wav,.m4a,audio/*" hidden onChange={(e) => choose(e.target.files?.[0])} aria-label="Audio file" />
      </div>
      <input className="input" placeholder="Track title" value={title} onChange={(e) => setTitle(e.target.value)} required aria-label="Track title" />
      <div style={{ display: 'flex', gap: 16, alignItems: 'center' }}>
        <label className="field" style={{ width: 120 }}><span style={{ fontSize: 13, fontWeight: 700 }}>Track #</span>
          <input className="input" type="number" min={1} value={number} onChange={(e) => setNumber(Number(e.target.value))} />
        </label>
        <label className="check" style={{ marginTop: 22 }}><input type="checkbox" checked={explicit} onChange={(e) => setExplicit(e.target.checked)} /> Explicit</label>
      </div>
      {progress !== null && <div className="bar" aria-label="Upload progress"><i style={{ width: `${progress}%` }} /></div>}
      <button type="submit" className="btn btn-primary" disabled={!file || !title.trim() || progress !== null}>
        {progress !== null ? `Uploading ${Math.round(progress)}%` : 'Upload & transcode'}
      </button>
    </form>
  );
}

const FILTERS: (TrackStatus | 'ALL')[] = ['ALL', 'PROCESSING', 'FAILED', 'READY', 'DRAFT'];

function Dashboard({ refreshKey }: { refreshKey: number }) {
  const [status, setStatus] = useState<TrackStatus | 'ALL'>('ALL');
  const toast = useToast();
  const client = useQueryClient();
  const tracks = useQuery({
    queryKey: ['admin-tracks', status, refreshKey],
    queryFn: () => api.adminTracks(status === 'ALL' ? undefined : status),
    refetchInterval: (q) => (q.state.data?.items.some((t) => t.status === 'PROCESSING') ? 2000 : 15000),
  });
  const retry = async (t: AdminTrack) => {
    try {
      await api.retranscode(t.id);
      toast(`Retrying “${t.title}”`);
      await client.invalidateQueries({ queryKey: ['admin-tracks'] });
    } catch (err) {
      toast(errorText(err), true);
    }
  };
  return (
    <section className="section">
      <div className="section-head">
        <h2 className="section-title">Processing status</h2>
        <div style={{ display: 'flex', gap: 6 }}>
          {FILTERS.map((f) => (
            <button key={f} type="button" className={`chip${status === f ? ' active' : ''}`} onClick={() => setStatus(f)}>
              {f === 'ALL' ? 'All' : f[0] + f.slice(1).toLowerCase()}
            </button>
          ))}
        </div>
      </div>
      <table className="table">
        <thead><tr><th>Track</th><th>Status</th><th>Duration</th><th>Loudness</th><th>Updated</th><th /></tr></thead>
        <tbody>
          {(tracks.data?.items ?? []).map((t) => (
            <tr key={t.id}>
              <td><strong>{t.title}</strong><div className="faint" style={{ fontSize: 12.5 }}>#{t.trackNumber} · {t.id.slice(0, 8)}</div></td>
              <td>
                <span className={`status ${t.status}`}>{t.status}</span>
                {t.failureReason && <div className="field-error" style={{ maxWidth: 320 }} title={t.failureReason}>{t.failureReason.slice(0, 90)}</div>}
              </td>
              <td className="tnum">{duration(t.durationMs)}</td>
              <td className="tnum">{t.loudnessLufs != null ? `${t.loudnessLufs.toFixed(1)} LUFS` : '–'}</td>
              <td className="faint">{relativeDate(t.updatedAt)}</td>
              <td>{t.status === 'FAILED' && <button type="button" className="btn btn-ghost btn-sm" onClick={() => retry(t)}>Retry</button>}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {tracks.data && tracks.data.items.length === 0 && <p className="muted" style={{ padding: 12 }}>No tracks with this status.</p>}
    </section>
  );
}

export function AdminPage() {
  const { isAdmin } = useAuth();
  const scrolled = useScrolled(40);
  const [artist, setArtist] = useState<Picked | null>(null);
  const [album, setAlbum] = useState<Picked | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  useEffect(() => setAlbum(null), [artist?.id]);
  if (!isAdmin) return <Navigate to="/" replace />;
  return (
    <div className="hero-tint" style={{ ['--hue' as string]: 140 }}>
      <TopBar solid={scrolled} />
      <div className="page">
        <h1 className="greeting">Catalog admin</h1>
        <p className="muted" style={{ marginTop: 6 }}>Add music in three steps. Uploads go straight to storage, then the transcoder makes HLS renditions.</p>
        <div className="admin-grid section" style={{ marginTop: 20 }}>
          <ArtistStep artist={artist} onDone={setArtist} />
          <AlbumStep artist={artist} album={album} onDone={setAlbum} />
          <TrackStep album={album} onUploaded={() => setRefreshKey((k) => k + 1)} />
        </div>
        <Dashboard refreshKey={refreshKey} />
      </div>
    </div>
  );
}
