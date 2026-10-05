import type { Problem, Tokens } from './types';

/**
 * Fetch wrapper for /api/v1 with bearer auth.
 *
 * Tokens: the access token lives in memory only; the refresh token is kept in localStorage so a reload keeps the
 * session. The API rotates refresh tokens and treats a reused one as theft (the whole session is revoked), so refreshes
 * are single-flight within a tab and serialized across tabs with the Web Locks API: inside the lock we re-read the
 * stored token, and if another tab already rotated it we just use the new one.
 */

const REFRESH_KEY = 'cadence.refreshToken';
export const API = '/api/v1';

export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem;

  constructor(problem: Problem) {
    super(problem.detail ?? problem.title ?? `HTTP ${problem.status}`);
    this.status = problem.status;
    this.problem = problem;
  }

  get code(): string | undefined {
    return this.problem.code;
  }
}

let accessToken: string | null = null;
let refreshing: Promise<boolean> | null = null;
const listeners = new Set<(signedIn: boolean) => void>();

export function onAuthChange(listener: (signedIn: boolean) => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function storedRefreshToken(): string | null {
  try {
    return localStorage.getItem(REFRESH_KEY);
  } catch {
    return null;
  }
}

function storeRefreshToken(token: string | null) {
  try {
    if (token) localStorage.setItem(REFRESH_KEY, token);
    else localStorage.removeItem(REFRESH_KEY);
  } catch {
    // storage unavailable: the session just won't survive a reload
  }
}

export function setTokens(tokens: Tokens) {
  accessToken = tokens.accessToken;
  storeRefreshToken(tokens.refreshToken);
  listeners.forEach((l) => l(true));
}

export function clearTokens() {
  const wasSignedIn = accessToken !== null || storedRefreshToken() !== null;
  accessToken = null;
  storeRefreshToken(null);
  if (wasSignedIn) listeners.forEach((l) => l(false));
}

export function hasSession(): boolean {
  return accessToken !== null || storedRefreshToken() !== null;
}

export function currentAccessToken(): string | null {
  return accessToken;
}

async function withRefreshLock<T>(work: () => Promise<T>): Promise<T> {
  const locks = (navigator as Navigator & { locks?: LockManager }).locks;
  return locks ? locks.request('cadence-token-refresh', work) : work();
}

/** Gets a new access token with the stored refresh token. @returns false when the session is gone */
export function refreshSession(): Promise<boolean> {
  if (!refreshing) {
    const before = storedRefreshToken();
    refreshing = withRefreshLock(async () => {
      const current = storedRefreshToken();
      if (!current) return false;
      if (current !== before && accessToken) return true; // another tab rotated it
      const response = await fetch(`${API}/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: current }),
      });
      if (!response.ok) {
        if (response.status === 401 || response.status === 400) clearTokens();
        return false;
      }
      setTokens((await response.json()) as Tokens);
      return true;
    }).finally(() => {
      refreshing = null;
    });
  }
  return refreshing;
}

export interface RequestOptions {
  method?: string;
  body?: unknown;
  headers?: Record<string, string>;
  auth?: boolean;
  signal?: AbortSignal;
}

async function toError(response: Response): Promise<ApiError> {
  let problem: Problem = { status: response.status, title: response.statusText };
  try {
    const text = await response.text();
    if (text) problem = { ...problem, ...(JSON.parse(text) as Problem) };
  } catch {
    // not JSON
  }
  return new ApiError(problem);
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, headers = {}, auth = true, signal } = options;
  const send = () => {
    const finalHeaders: Record<string, string> = { Accept: 'application/json', ...headers };
    if (body !== undefined) finalHeaders['Content-Type'] = 'application/json';
    if (auth && accessToken) finalHeaders.Authorization = `Bearer ${accessToken}`;
    return fetch(path.startsWith('http') ? path : `${API}${path}`, {
      method,
      headers: finalHeaders,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    });
  };

  if (auth && !accessToken && storedRefreshToken()) await refreshSession();
  let response = await send();
  if (response.status === 401 && auth && storedRefreshToken()) {
    if (await refreshSession()) response = await send();
  }
  if (!response.ok) throw await toError(response);
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}
