import { useState, type ReactNode } from 'react';
import { NavLink, useLocation, useNavigate } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { api } from '../api/endpoints';
import { keys, useFollowedArtists, useLikedTracks, useMyPlaylists, useSavedAlbums } from '../api/hooks';
import { useAuth } from '../auth/AuthProvider';
import { plural } from '../lib/format';
import { usePlayer } from '../player/PlayerProvider';
import { Artwork } from './Artwork';
import { HeartFilledIcon, HomeFilledIcon, HomeIcon, LibraryIcon, PlusIcon, SearchIcon, ShieldIcon } from './Icons';
import { useToast } from './Toasts';

type Filter = 'all' | 'playlists' | 'albums' | 'artists';

export function Brand() {
  return (
    <div className="brand">
      <span className="brand-mark" aria-hidden="true"><i /><i /><i /><i /></span>
      <span className="label">Cadence</span>
    </div>
  );
}

export function Sidebar() {
  const location = useLocation();
  const navigate = useNavigate();
  const client = useQueryClient();
  const toast = useToast();
  const { isAdmin } = useAuth();
  const { queue } = usePlayer();
  const [filter, setFilter] = useState<Filter>('all');
  const playlists = useMyPlaylists().data ?? [];
  const albums = useSavedAlbums().data ?? [];
  const artists = useFollowedArtists().data ?? [];
  const liked = useLikedTracks().data ?? [];
  const playingFrom = queue.context?.sourceId;

  const createPlaylist = async () => {
    try {
      const created = await api.createPlaylist(`My Playlist #${playlists.length + 1}`);
      await client.invalidateQueries({ queryKey: keys.playlists });
      navigate(`/playlist/${created.id}`);
    } catch {
      toast('Could not create a playlist', true);
    }
  };

  const item = (key: string, href: string, title: string, sub: string, art: ReactNode) => (
    <NavLink key={key} to={href} className={({ isActive }) => `lib-item${isActive ? ' active' : ''}`}>
      {art}
      <div style={{ minWidth: 0 }}>
        <div className={`title ellipsis${playingFrom === key ? ' playing' : ''}`}>{title}</div>
        <div className="sub ellipsis">{sub}</div>
      </div>
    </NavLink>
  );

  return (
    <aside className="sidebar" aria-label="Navigation">
      <nav className="panel">
        <Brand />
        <NavLink to="/" end className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}>
          {location.pathname === '/' ? <HomeFilledIcon /> : <HomeIcon />}<span className="label">Home</span>
        </NavLink>
        <NavLink to="/search" className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}>
          <SearchIcon /><span className="label">Search</span>
        </NavLink>
        {isAdmin && (
          <NavLink to="/admin" className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}>
            <ShieldIcon /><span className="label">Catalog admin</span>
          </NavLink>
        )}
      </nav>
      <section className="panel library" aria-label="Your library">
        <div className="library-head">
          <h2><LibraryIcon width={22} height={22} /><span>Your Library</span></h2>
          <button type="button" className="icon-btn" aria-label="Create playlist" title="Create playlist" onClick={createPlaylist}>
            <PlusIcon />
          </button>
        </div>
        <div className="library-filters" role="tablist">
          {(['all', 'playlists', 'albums', 'artists'] as Filter[]).map((f) => (
            <button key={f} type="button" role="tab" aria-selected={filter === f} className={`chip${filter === f ? ' active' : ''}`}
                    onClick={() => setFilter(f)}>
              {f[0].toUpperCase() + f.slice(1)}
            </button>
          ))}
        </div>
        <div className="library-list scroll">
          {(filter === 'all' || filter === 'playlists') && item('liked', '/collection/tracks', 'Liked Songs',
            `Playlist • ${plural(liked.length, 'song')}`, <div className="art liked-art" style={{ width: 48 }}><HeartFilledIcon /></div>)}
          {(filter === 'all' || filter === 'playlists') && playlists.map((p) =>
            item(p.id, `/playlist/${p.id}`, p.name, `Playlist • ${plural(p.trackCount, 'song')}`,
              <Artwork seed={p.id} title={p.name} src={p.coverUrl} size={48} />))}
          {(filter === 'all' || filter === 'albums') && albums.filter((a) => a.album).map((a) =>
            item(a.album.id, `/album/${a.album.id}`, a.album.title, 'Album',
              <Artwork seed={a.album.id} title={a.album.title} src={a.album.coverUrl} size={48} />))}
          {(filter === 'all' || filter === 'artists') && artists.filter((a) => a.artist).map((a) =>
            item(a.artist.id, `/artist/${a.artist.id}`, a.artist.name, 'Artist',
              <Artwork seed={a.artist.id} title={a.artist.name} round size={48} />))}
        </div>
      </section>
    </aside>
  );
}
