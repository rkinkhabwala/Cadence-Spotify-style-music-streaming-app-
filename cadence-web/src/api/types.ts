// Shapes returned by cadence-api (/api/v1). Kept in one place so the UI is typed end to end.

export type UUID = string;

export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}

export interface ArtistRef {
  id: UUID;
  name: string;
}

export interface ArtistCredit {
  id: UUID;
  name: string | null;
  role: 'PRIMARY' | 'FEATURED';
}

export interface AlbumRef {
  id: UUID;
  title: string;
  coverUrl: string | null;
}

export type TrackStatus = 'DRAFT' | 'PROCESSING' | 'READY' | 'FAILED';

export interface TrackSummary {
  id: UUID;
  title: string;
  durationMs: number | null;
  explicit: boolean;
  discNumber: number;
  trackNumber: number;
  status: TrackStatus;
  playCount: number;
  album: AlbumRef | null;
  artists: ArtistCredit[];
}

export interface ArtistDetail {
  id: UUID;
  name: string;
  bio: string | null;
  imageUrl: string | null;
  verified: boolean;
  monthlyListeners: number;
  topTracks: TrackSummary[];
}

export type AlbumType = 'ALBUM' | 'SINGLE' | 'EP';

export interface AlbumView {
  id: UUID;
  title: string;
  type: AlbumType;
  releaseDate: string;
  coverUrl: string | null;
  label: string | null;
  artist: ArtistRef;
  genres: string[];
}

export interface AlbumDetail extends AlbumView {
  totalDurationMs: number;
  tracks: TrackSummary[];
}

export interface AlbumSummary {
  id: UUID;
  title: string;
  type: AlbumType;
  releaseDate: string;
  coverUrl: string | null;
  artist: ArtistRef;
}

export type Visibility = 'PUBLIC' | 'PRIVATE';

export interface PlaylistView {
  id: UUID;
  ownerId: UUID;
  name: string;
  description: string | null;
  coverUrl: string | null;
  visibility: Visibility;
  collaborative: boolean;
  trackCount: number;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface PlaylistItem {
  trackId: UUID;
  track: TrackSummary | null;
  playable: boolean;
  addedBy: UUID;
  addedAt: string;
}

export interface PlaylistDetail extends PlaylistView {
  ownerName: string | null;
  tracks: Page<PlaylistItem>;
}

export interface LikedTrack {
  track: TrackSummary;
  likedAt: string;
}

export interface FollowedArtist {
  artist: ArtistRef;
  followedAt: string;
}

export interface SavedAlbum {
  album: AlbumRef;
  savedAt: string;
}

export interface Profile {
  id: UUID;
  email: string;
  displayName: string;
  avatarUrl: string | null;
  country: string | null;
  plan: 'FREE' | 'PREMIUM';
  roles: string[];
}

/** The refresh token is never in a body: it is an HttpOnly cookie (D86). */
export interface Tokens {
  accessToken: string;
  expiresIn: number;
}

export interface PlaybackStart {
  manifestUrl: string;
  expiresAt: string;
  durationMs: number | null;
}

export type PlaySource = 'PLAYLIST' | 'ALBUM' | 'SEARCH' | 'RADIO' | 'ARTIST' | 'LIBRARY' | 'OTHER';

export interface PlayView {
  playId: UUID;
  trackId: UUID;
  msPlayed: number;
  completed: boolean;
  skipped: boolean;
  counted: boolean;
}

export interface RecentlyPlayedItem {
  track: TrackSummary;
  playable: boolean;
  playedAt: string;
}

export interface TopTrackItem {
  track: TrackSummary;
  plays: number;
}

/** `position`: on recommended tracks, the slot in the recommender's list (sent back with play reports). */
export type ShelfItem =
  | { type: 'track'; track: TrackSummary; position?: number }
  | { type: 'album'; album: AlbumSummary };

/** `source` and `recommendationId` are set on the recommendation shelves ("made-for-you", "because-you-listened"). */
export interface Shelf {
  id: string;
  title: string;
  items: ShelfItem[];
  source?: 'recommender' | 'fallback';
  recommendationId?: string | null;
}

export interface Home {
  shelves: Shelf[];
}

export interface ArtistHit {
  id: UUID;
  name: string;
  imageUrl: string | null;
  verified: boolean;
}

export interface AlbumHit {
  id: UUID;
  title: string;
  type: AlbumType;
  releaseDate: string;
  coverUrl: string | null;
  artist: ArtistRef;
}

export interface PlaylistHit {
  id: UUID;
  name: string;
  description: string | null;
  coverUrl: string | null;
  trackCount: number;
  owner: { id: UUID; displayName: string | null };
}

export interface SearchResults {
  query: string;
  tracks?: Page<TrackSummary>;
  artists?: Page<ArtistHit>;
  albums?: Page<AlbumHit>;
  playlists?: Page<PlaylistHit>;
}

export type SuggestionType = 'track' | 'artist' | 'album' | 'playlist';

export interface Suggestion {
  type: SuggestionType;
  id: UUID;
  text: string;
  subtitle: string | null;
  imageUrl: string | null;
}

export interface AdminTrack {
  id: UUID;
  title: string;
  albumId: UUID;
  discNumber: number;
  trackNumber: number;
  explicit: boolean;
  isrc: string | null;
  status: TrackStatus;
  durationMs: number | null;
  loudnessLufs: number | null;
  failureReason: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface UploadUrl {
  uploadUrl: string;
  method: string;
  headers: Record<string, string>;
  objectKey: string;
  expiresAt: string;
}

export interface Problem {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  code?: string;
  errors?: { field: string; message: string }[];
}
