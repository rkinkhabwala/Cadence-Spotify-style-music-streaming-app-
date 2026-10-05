import type { Problem, Tokens } from './types';

/**
 * Fetch wrapper for /api/v1 with bearer auth.
 *
 * Tokens (D86): the access token lives in memory only. The refresh token is an HttpOnly, SameSite=Strict cookie scoped
 * to /api/v1/auth, so scripts never see it and the browser only sends it to the refresh and logout calls. On page load
 * the app calls /auth/refresh to restore the session from that cookie. The API rotates refresh tokens and treats a
 * reused one as theft (the whole session is revoked), so refreshes are single-flight within a tab and serialized across
 * tabs with the Web Locks API. The cookie jar is shared, so a tab that waited for the lock sends the token the previous
 * tab just received.
 */

/** Required by the cookie endpoints (refresh, logout) as CSRF protection; see RefreshCsrfGuard. */
export const CSRF_HEADER = 'X-Cadence-CSRF';
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
/** The last refresh was rejected: requests stop trying to refresh until the next sign-in. */
let sessionEnded = false;
let refreshing: Promise<boolean> | null = null;
const listeners = new Set<(signedIn: boolean) => void>();

export function onAuthChange(listener: (signedIn: boolean) => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function setTokens(tokens: Tokens) {
  accessToken = tokens.accessToken;
  sessionEnded = false;
  listeners.forEach((l) => l(true));
}

export function clearTokens() {
  const wasSignedIn = accessToken !== null;
  accessToken = null;
  sessionEnded = true;
  if (wasSignedIn) listeners.forEach((l) => l(false));
}

export function hasSession(): boolean {
  return accessToken !== null;
}

export function currentAccessToken(): string | null {
  return accessToken;
}

async function withRefreshLock<T>(work: () => Promise<T>): Promise<T> {
  const locks = (navigator as Navigator & { locks?: LockManager }).locks;
  return locks ? locks.request('cadence-token-refresh', work) : work();
}

/**
 * Gets a new access token using the refresh cookie (also how a reload restores the session).
 * @returns false when there is no session
 */
export function refreshSession(): Promise<boolean> {
  if (!refreshing) {
    refreshing = withRefreshLock(async () => {
      const response = await fetch(`${API}/auth/refresh`, {
        method: 'POST',
        headers: { Accept: 'application/json', [CSRF_HEADER]: '1' },
        credentials: 'same-origin',
      });
      if (!response.ok) {
        if (response.status === 401) clearTokens();
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
      credentials: 'same-origin',
      signal,
    });
  };

  if (auth && !accessToken && !sessionEnded) await refreshSession();
  let response = await send();
  if (response.status === 401 && auth && !sessionEnded) {
    if (await refreshSession()) response = await send();
  }
  if (!response.ok) throw await toError(response);
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}
