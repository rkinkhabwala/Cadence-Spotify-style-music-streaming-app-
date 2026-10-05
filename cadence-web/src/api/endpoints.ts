import { CSRF_HEADER, request } from './client';
import type {
  AdminTrack, AlbumDetail, AlbumView, ArtistDetail, FollowedArtist, Home, LikedTrack, Page, PlaybackStart, PlaylistDetail,
  PlaylistView, PlaySource, PlayView, Profile, RecentlyPlayedItem, SavedAlbum, SearchResults, Suggestion, Tokens,
  TopTrackItem, TrackStatus, UploadUrl, Visibility,
} from './types';

type ArtistView = { id: string; name: string };

const q = (params: Record<string, string | number | undefined | null>) => {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') search.set(k, String(v));
  });
  const s = search.toString();
  return s ? `?${s}` : '';
};

const ifMatch = (version: number) => ({ 'If-Match': `"${version}"` });

export const api = {
  // identity
  login: (email: string, password: string) =>
    request<Tokens>('/auth/login', { method: 'POST', body: { email, password }, auth: false }),
  register: (email: string, password: string, displayName: string) =>
    request<Tokens>('/auth/register', { method: 'POST', body: { email, password, displayName }, auth: false }),
  logout: () => request<void>('/auth/logout', { method: 'POST', headers: { [CSRF_HEADER]: '1' }, auth: false }),
  me: () => request<Profile>('/me'),

  // catalog
  artist: (id: string) => request<ArtistDetail>(`/artists/${id}`),
  artistAlbums: (id: string, cursor?: string) => request<Page<AlbumView>>(`/artists/${id}/albums${q({ limit: 50, cursor })}`),
  album: (id: string) => request<AlbumDetail>(`/albums/${id}`),

  // search
  search: (text: string, types?: string, limit = 10, cursor?: string) =>
    request<SearchResults>(`/search${q({ q: text, types, limit, cursor })}`),
  suggest: (text: string, signal?: AbortSignal) =>
    request<{ query: string; items: Suggestion[] }>(`/search/suggest${q({ q: text })}`, { signal }),

  // activity
  home: () => request<Home>('/home'),
  recentlyPlayed: () => request<Page<RecentlyPlayedItem>>('/me/recently-played'),
  topTracks: (range: 'short' | 'medium' | 'long', limit = 50) =>
    request<Page<TopTrackItem>>(`/me/top/tracks${q({ range, limit })}`),
  reportPlay: (body: { playId: string; trackId: string; msPlayed: number; source: PlaySource; sourceId?: string | null;
    completed: boolean; skipped: boolean; sessionId?: string; recommendationId?: string; position?: number }) =>
    request<PlayView>('/activity/plays', { method: 'POST', body }),

  // streaming
  startPlayback: (trackId: string) => request<PlaybackStart>(`/playback/${trackId}`, { method: 'POST' }),

  // library
  myPlaylists: () => request<Page<PlaylistView>>('/me/playlists?limit=100'),
  playlist: (id: string, cursor?: string) => request<PlaylistDetail>(`/playlists/${id}${q({ limit: 100, cursor })}`),
  createPlaylist: (name: string, visibility: Visibility = 'PRIVATE') =>
    request<PlaylistView>('/playlists', { method: 'POST', body: { name, visibility } }),
  updatePlaylist: (id: string, version: number, body: { name?: string; description?: string; visibility?: Visibility }) =>
    request<PlaylistView>(`/playlists/${id}`, { method: 'PATCH', body, headers: ifMatch(version) }),
  deletePlaylist: (id: string) => request<void>(`/playlists/${id}`, { method: 'DELETE' }),
  addToPlaylist: (id: string, trackIds: string[]) =>
    request<PlaylistView>(`/playlists/${id}/tracks`, { method: 'POST', body: { trackIds } }),
  removeFromPlaylist: (id: string, trackIds: string[]) =>
    request<PlaylistView>(`/playlists/${id}/tracks`, { method: 'DELETE', body: { trackIds } }),
  reorderPlaylist: (id: string, trackId: string, afterTrackId: string | null) =>
    request<PlaylistView>(`/playlists/${id}/tracks/reorder`, { method: 'PUT', body: { trackId, afterTrackId } }),
  likedTracks: (cursor?: string) => request<Page<LikedTrack>>(`/me/likes/tracks${q({ limit: 100, cursor })}`),
  like: (trackId: string) => request<void>(`/me/likes/tracks/${trackId}`, { method: 'PUT' }),
  unlike: (trackId: string) => request<void>(`/me/likes/tracks/${trackId}`, { method: 'DELETE' }),
  followedArtists: (cursor?: string) => request<Page<FollowedArtist>>(`/me/following/artists${q({ limit: 100, cursor })}`),
  follow: (artistId: string) => request<void>(`/me/following/artists/${artistId}`, { method: 'PUT' }),
  unfollow: (artistId: string) => request<void>(`/me/following/artists/${artistId}`, { method: 'DELETE' }),
  savedAlbums: (cursor?: string) => request<Page<SavedAlbum>>(`/me/albums${q({ limit: 100, cursor })}`),
  saveAlbum: (albumId: string) => request<void>(`/me/albums/${albumId}`, { method: 'PUT' }),
  unsaveAlbum: (albumId: string) => request<void>(`/me/albums/${albumId}`, { method: 'DELETE' }),

  // admin
  adminTracks: (status?: TrackStatus, cursor?: string) =>
    request<Page<AdminTrack>>(`/admin/tracks${q({ status, cursor, limit: 50 })}`),
  adminTrack: (id: string) => request<AdminTrack>(`/admin/tracks/${id}`),
  createArtist: (body: { name: string; bio?: string; verified?: boolean }) =>
    request<ArtistView>('/admin/artists', { method: 'POST', body }),
  createAlbum: (body: { title: string; artistId: string; releaseDate: string; type: string; label?: string; genres?: string[] }) =>
    request<AlbumView>('/admin/albums', { method: 'POST', body }),
  createTrack: (body: { title: string; albumId: string; trackNumber: number; explicit?: boolean }) =>
    request<AdminTrack>('/admin/tracks', { method: 'POST', body }),
  uploadUrl: (trackId: string, extension: string, sizeBytes: number) =>
    request<UploadUrl>(`/admin/tracks/${trackId}/upload-url`, { method: 'POST', body: { extension, sizeBytes } }),
  uploadComplete: (trackId: string) => request<AdminTrack>(`/admin/tracks/${trackId}/upload-complete`, { method: 'POST' }),
  retranscode: (trackId: string) => request<AdminTrack>(`/admin/tracks/${trackId}/retranscode`, { method: 'POST' }),
};
