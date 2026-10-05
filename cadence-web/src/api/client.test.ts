import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, clearTokens, hasSession, request, setTokens } from './client';

describe('api client', () => {
  beforeEach(() => clearTokens());
  afterEach(() => vi.unstubAllGlobals());

  it('refreshes an expired access token once for concurrent requests and retries them', async () => {
    setTokens({ accessToken: 'old', refreshToken: 'r1', expiresIn: 900 });
    let refreshes = 0;
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (url.endsWith('/auth/refresh')) {
        refreshes++;
        expect(JSON.parse(String(init.body))).toEqual({ refreshToken: 'r1' });
        await new Promise((r) => setTimeout(r, 10));
        return new Response(JSON.stringify({ accessToken: 'new', refreshToken: 'r2', expiresIn: 900 }));
      }
      const auth = (init.headers as Record<string, string>).Authorization;
      return auth === 'Bearer new' ? new Response(JSON.stringify({ ok: url })) : new Response('', { status: 401 });
    }));

    const results = await Promise.all([request<{ ok: string }>('/me'), request<{ ok: string }>('/home')]);

    expect(results.map((r) => r.ok)).toEqual(['/api/v1/me', '/api/v1/home']);
    expect(refreshes).toBe(1);
    expect(localStorage.getItem('cadence.refreshToken')).toBe('r2');
  });

  it('ends the session when the refresh token is rejected', async () => {
    setTokens({ accessToken: 'old', refreshToken: 'stolen', expiresIn: 900 });
    vi.stubGlobal('fetch', vi.fn(async (url: string) => url.endsWith('/auth/refresh')
      ? new Response(JSON.stringify({ status: 401, code: 'refresh-token-reused' }), { status: 401 })
      : new Response('', { status: 401 })));

    await expect(request('/me')).rejects.toBeInstanceOf(ApiError);
    expect(hasSession()).toBe(false);
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
