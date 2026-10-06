import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createQueryClient, routes } from '../App';
import { clearTokens } from '../api/client';
import { AuthProvider } from '../auth/AuthProvider';
import { ToastProvider } from '../components/Toasts';
import { AudioEngine } from '../player/engine';
import { PlayerProvider } from '../player/PlayerProvider';
import { album, fakeApi, profile, tokens } from './fixtures';

class FakeEngine extends AudioEngine {
  sources: string[] = [];

  override load(manifestUrl: string) {
    this.loads++;
    this.sources.push(manifestUrl);
    this.audio.setAttribute('src', manifestUrl);
  }
}

const start = (t: string) => ({ manifestUrl: `http://localhost/api/v1/playback/${t}/master.m3u8?token=x`,
  expiresAt: '2030-01-01T00:00:00Z', durationMs: 180_000 });

describe('free plan rules in the player (D96)', () => {
  let engine: FakeEngine;

  beforeEach(() => {
    clearTokens();
    engine = new FakeEngine(document.createElement('audio'));
  });
  afterEach(() => vi.unstubAllGlobals());

  function renderAlbum(routesOverride: Record<string, unknown>) {
    const api = fakeApi({
      'POST /auth/refresh': tokens,
      'GET /me': profile,
      'GET /albums/al1': album,
      'POST /playback/t1': start('t1'),
      'POST /playback/t2': start('t2'),
      ...routesOverride,
    });
    vi.stubGlobal('fetch', vi.fn(api.fetchMock));
    render(
      <QueryClientProvider client={createQueryClient()}>
        <ToastProvider>
          <PlayerProvider engine={engine}>
            <AuthProvider><RouterProvider router={createMemoryRouter(routes, { initialEntries: ['/album/al1'] })} /></AuthProvider>
          </PlayerProvider>
        </ToastProvider>
      </QueryClientProvider>,
    );
    return api;
  }

  it('a refused skip keeps the current track and explains why', async () => {
    const user = userEvent.setup();
    const api = renderAlbum({
      'POST /playback/t1/skip': () => new Response(JSON.stringify({ status: 429, code: 'skip-limit-reached', detail: 'x' }),
        { status: 429, headers: { 'Retry-After': '600' } }),
    });
    await user.click(await screen.findByRole('button', { name: 'Play Night Ferries' }));
    await waitFor(() => expect(engine.loads).toBe(1));
    const player = screen.getByRole('contentinfo', { name: 'Player' });

    await user.click(within(player).getByRole('button', { name: 'Next' }));

    expect(await screen.findByText(/out of skips for now.*10 min/)).toBeInTheDocument();
    expect(engine.loads).toBe(1);
    expect(within(player).getByText('Track 1')).toBeInTheDocument();
    const skip = api.calls.find((c) => c.path === '/playback/t1/skip');
    expect((skip?.body as { playId: string }).playId).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('an allowed skip moves on', async () => {
    const user = userEvent.setup();
    renderAlbum({ 'POST /playback/t1/skip': { remaining: 5, limit: 6 } });
    await user.click(await screen.findByRole('button', { name: 'Play Night Ferries' }));
    await waitFor(() => expect(engine.loads).toBe(1));

    await user.click(within(screen.getByRole('contentinfo', { name: 'Player' })).getByRole('button', { name: 'Next' }));

    await waitFor(() => expect(engine.sources[1]).toContain('/playback/t2/'));
  });

  it('an ad slot plays before the track', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
      renderAlbum({ 'POST /playback/t1': { ...start('t1'), adSlot: { type: 'placeholder', durationMs: 5_000 } } });
      await user.click(await screen.findByRole('button', { name: 'Play Night Ferries' }));

      expect(await screen.findByText(/Ad break · your track starts in/)).toBeInTheDocument();
      expect(engine.loads).toBe(0);
      await act(async () => { vi.advanceTimersByTime(5_100); });

      await waitFor(() => expect(engine.loads).toBe(1));
      expect(screen.queryByText(/Ad break/)).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });
});
