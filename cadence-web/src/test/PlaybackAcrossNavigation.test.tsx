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
import { album, fakeApi, profile, tokens, track } from './fixtures';

/** Records loads instead of starting hls.js (jsdom has no MediaSource). */
class FakeEngine extends AudioEngine {
  sources: string[] = [];

  override load(manifestUrl: string) {
    this.loads++;
    this.sources.push(manifestUrl);
    this.audio.setAttribute('src', manifestUrl);
  }
}

function tick(audio: HTMLAudioElement, seconds: number) {
  Object.defineProperty(audio, 'currentTime', { configurable: true, writable: true, value: seconds });
  audio.dispatchEvent(new Event('timeupdate'));
}

describe('spec 9 Phase 2 AC5: the web player continues playback across page navigation', () => {
  let engine: FakeEngine;
  let api: ReturnType<typeof fakeApi>;

  beforeEach(() => {
    clearTokens();
    localStorage.setItem('cadence.refreshToken', 'refresh-1');
    engine = new FakeEngine(document.createElement('audio'));
    api = fakeApi({
      'POST /auth/refresh': tokens,
      'GET /me': profile,
      'GET /albums/al1': album,
      'GET /artists/ar1': { id: 'ar1', name: 'Velvet Static', bio: null, imageUrl: null, verified: true, monthlyListeners: 0, topTracks: album.tracks },
      'GET /artists/ar1/albums': { items: [album], nextCursor: null },
      'GET /home': { shelves: [{ id: 'recently-played', title: 'Recently played', items: [{ type: 'track', track: track(1) }] }] },
      'POST /playback/t1': { manifestUrl: 'http://localhost/api/v1/playback/t1/master.m3u8?token=x', expiresAt: '2030-01-01T00:00:00Z', durationMs: 180_000 },
      'POST /playback/t2': { manifestUrl: 'http://localhost/api/v1/playback/t2/master.m3u8?token=y', expiresAt: '2030-01-01T00:00:00Z', durationMs: 180_000 },
      'POST /activity/plays': (_u: URL, init: RequestInit) => ({ ...JSON.parse(String(init.body)), counted: true }),
    });
    vi.stubGlobal('fetch', vi.fn(api.fetchMock));
  });

  afterEach(() => vi.unstubAllGlobals());

  function renderApp(initial: string) {
    const router = createMemoryRouter(routes, { initialEntries: [initial] });
    render(
      <QueryClientProvider client={createQueryClient()}>
        <ToastProvider>
          <PlayerProvider engine={engine}>
            <AuthProvider><RouterProvider router={router} /></AuthProvider>
          </PlayerProvider>
        </ToastProvider>
      </QueryClientProvider>,
    );
    return router;
  }

  it('keeps the same audio element playing, without reloading, through several pages', async () => {
    const user = userEvent.setup();
    const router = renderApp('/album/al1');

    await user.click(await screen.findByRole('button', { name: 'Play Night Ferries' }));
    await waitFor(() => expect(engine.loads).toBe(1));
    expect(engine.sources[0]).toContain('/playback/t1/master.m3u8');
    const audio = engine.audio;
    expect(audio.paused).toBe(false);
    for (let s = 0; s <= 12; s += 0.25) act(() => tick(audio, s));   // 12 s heard before navigating
    const player = screen.getByRole('contentinfo', { name: 'Player' });
    expect(within(player).getByText('Track 1')).toBeInTheDocument();

    // sidebar navigation, a link inside the player bar, a programmatic navigation and the history stack
    await user.click(screen.getByRole('link', { name: 'Search' }));
    await screen.findByRole('combobox', { name: 'What do you want to play?' });
    await user.click(within(player).getByRole('link', { name: 'Velvet Static' }));
    await screen.findByRole('heading', { name: 'Velvet Static', level: 1 });
    await act(() => router.navigate('/'));
    await screen.findByRole('heading', { name: /Good|Up late/ });
    await act(() => router.navigate(-1));
    await screen.findByRole('heading', { name: 'Velvet Static', level: 1 });

    expect(engine.audio).toBe(audio);
    expect(engine.loads).toBe(1);
    expect(audio.paused).toBe(false);
    expect(audio.getAttribute('src')).toContain('/playback/t1/');
    expect(within(screen.getByRole('contentinfo', { name: 'Player' })).getByText('Track 1')).toBeInTheDocument();

    // listening continued across those pages: the 30-second report is sent for the same playback
    for (let s = 12.25; s <= 31; s += 0.25) act(() => tick(audio, s));
    await waitFor(() => expect(api.calls.filter((c) => c.path === '/activity/plays')).toHaveLength(1));
    const report = api.calls.find((c) => c.path === '/activity/plays')!.body as Record<string, unknown>;
    expect(report).toMatchObject({ trackId: 't1', source: 'ALBUM', sourceId: 'al1', completed: false, skipped: false });
    expect(report.msPlayed as number).toBeGreaterThanOrEqual(30_000);
  });

  it('player controls drive the queue: next, shuffle, repeat and pause', async () => {
    const user = userEvent.setup();
    renderApp('/album/al1');
    await user.click(await screen.findByRole('button', { name: 'Play Night Ferries' }));
    await waitFor(() => expect(engine.loads).toBe(1));
    const player = screen.getByRole('contentinfo', { name: 'Player' });

    for (let s = 0; s <= 5; s += 0.25) act(() => tick(engine.audio, s));
    await user.click(within(player).getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(engine.sources[1]).toContain('/playback/t2/'));
    expect(within(player).getByText('Track 2')).toBeInTheDocument();
    // moving on after 5 s of listening reports a skip of the first playback
    await waitFor(() => expect(api.calls.some((c) => c.path === '/activity/plays'
      && (c.body as { skipped: boolean; trackId: string }).skipped && (c.body as { trackId: string }).trackId === 't1')).toBe(true));

    await user.click(within(player).getByRole('button', { name: 'Enable shuffle' }));
    expect(within(player).getByRole('button', { name: 'Disable shuffle' })).toHaveAttribute('aria-pressed', 'true');
    await user.click(within(player).getByRole('button', { name: 'Enable repeat' }));
    await user.click(within(player).getByRole('button', { name: 'Enable repeat one' }));
    expect(within(player).getByRole('button', { name: 'Disable repeat' })).toBeInTheDocument();

    await user.click(within(player).getByRole('button', { name: 'Pause' }));
    expect(engine.audio.paused).toBe(true);
    expect(within(player).getByRole('button', { name: 'Play' })).toBeInTheDocument();
  });

  it('sends signed-out users to the login page', async () => {
    localStorage.clear();
    renderApp('/album/al1');
    expect(await screen.findByRole('heading', { name: 'Log in to Cadence' })).toBeInTheDocument();
  });
});
