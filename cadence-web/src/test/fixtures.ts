import type { AlbumDetail, TrackSummary } from '../api/types';

export function track(n: number, overrides: Partial<TrackSummary> = {}): TrackSummary {
  return {
    id: `t${n}`, title: `Track ${n}`, durationMs: 180_000, explicit: false, discNumber: 1, trackNumber: n,
    status: 'READY', playCount: 0, album: { id: 'al1', title: 'Night Ferries', coverUrl: null },
    artists: [{ id: 'ar1', name: 'Velvet Static', role: 'PRIMARY' }], ...overrides,
  };
}

export const album: AlbumDetail = {
  id: 'al1', title: 'Night Ferries', type: 'ALBUM', releaseDate: '2020-10-09', coverUrl: null, label: null,
  artist: { id: 'ar1', name: 'Velvet Static' }, genres: ['Lo-fi'], totalDurationMs: 540_000,
  tracks: [track(1), track(2), track(3)],
};

type Handler = (url: URL, init: RequestInit) => unknown;

/**
 * A fake API: routes are "METHOD /path" (path without /api/v1, query ignored). Unknown routes answer an empty page.
 * Every call is recorded.
 */
export function fakeApi(routes: Record<string, unknown | Handler>) {
  const calls: { method: string; path: string; body: unknown }[] = [];
  const fetchMock = async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = new URL(typeof input === 'string' ? input : input.toString(), 'http://localhost');
    const method = (init.method ?? 'GET').toUpperCase();
    const path = url.pathname.replace(/^\/api\/v1/, '');
    const body = init.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ method, path, body });
    const key = `${method} ${path}`;
    const route = key in routes ? routes[key] : undefined;
    const value = typeof route === 'function' ? (route as Handler)(url, init) : route;
    if (value === undefined && !(key in routes)) {
      return new Response(JSON.stringify({ items: [], nextCursor: null }), { status: 200 });
    }
    if (value instanceof Response) return value;
    return new Response(value === null ? null : JSON.stringify(value), { status: value === null ? 204 : 200 });
  };
  return { fetchMock, calls };
}

export const profile = {
  id: 'u1', email: 'demo@cadence.local', displayName: 'Demo Listener', avatarUrl: null, country: null, plan: 'FREE',
  roles: ['LISTENER'],
};

export const tokens = { accessToken: 'access-1', expiresIn: 900 };
