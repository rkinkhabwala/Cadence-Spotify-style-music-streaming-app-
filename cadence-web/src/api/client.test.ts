import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, clearTokens, currentAccessToken, hasSession, refreshSession, request, setTokens } from './client';

describe('api client', () => {
  beforeEach(() => clearTokens());
  afterEach(() => vi.unstubAllGlobals());

  it('refreshes an expired access token once for concurrent requests and retries them', async () => {
    setTokens({ accessToken: 'old', expiresIn: 900 });
    let refreshes = 0;
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (url.endsWith('/auth/refresh')) {
        refreshes++;
        expect(init.body).toBeUndefined(); // the refresh token is the HttpOnly cookie, never in the body
        expect(init.credentials).toBe('same-origin');
        expect((init.headers as Record<string, string>)['X-Cadence-CSRF']).toBe('1');
        await new Promise((r) => setTimeout(r, 10));
        return new Response(JSON.stringify({ accessToken: 'new', expiresIn: 900 }));
      }
      const auth = (init.headers as Record<string, string>).Authorization;
      return auth === 'Bearer new' ? new Response(JSON.stringify({ ok: url })) : new Response('', { status: 401 });
    }));

    const results = await Promise.all([request<{ ok: string }>('/me'), request<{ ok: string }>('/home')]);

    expect(results.map((r) => r.ok)).toEqual(['/api/v1/me', '/api/v1/home']);
    expect(refreshes).toBe(1);
  });

  it('restores a session after a reload from the refresh cookie and keeps tokens out of web storage', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ accessToken: 'restored', expiresIn: 900 }))));

    expect(await refreshSession()).toBe(true);

    expect(currentAccessToken()).toBe('restored');
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it('ends the session when the refresh cookie is rejected and stops retrying', async () => {
    setTokens({ accessToken: 'old', expiresIn: 900 });
    const fetchMock = vi.fn(async (url: string) => url.endsWith('/auth/refresh')
      ? new Response(JSON.stringify({ status: 401, code: 'refresh-token-reused' }), { status: 401 })
      : new Response('', { status: 401 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(request('/me')).rejects.toBeInstanceOf(ApiError);
    expect(hasSession()).toBe(false);
    const refreshCalls = () => fetchMock.mock.calls.filter(([url]) => url.endsWith('/auth/refresh')).length;
    expect(refreshCalls()).toBe(1);

    await expect(request('/me')).rejects.toBeInstanceOf(ApiError);
    expect(refreshCalls()).toBe(1);
  });

  it('turns problem details into ApiError with the code', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      status: 409, title: 'Conflict', detail: 'playId belongs to a different playback', code: 'play-mismatch',
    }), { status: 409 })));
    const error = await request('/activity/plays', { method: 'POST', body: {} }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe('play-mismatch');
    expect((error as ApiError).message).toBe('playId belongs to a different playback');
  });
});
