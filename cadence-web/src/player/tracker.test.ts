import { describe, expect, it, vi } from 'vitest';
import { PlayTracker, type PlayReport } from './tracker';

function tracker() {
  const reports: PlayReport[] = [];
  const t = new PlayTracker('t1', 'ALBUM', 'al1', (r) => reports.push(r), 'play-1');
  return { t, reports };
}

/** Simulates playback from `from` to `to` seconds with 250 ms time updates. */
function play(t: PlayTracker, from: number, to: number) {
  for (let s = from; s <= to + 1e-9; s += 0.25) t.onTime(s);
}

describe('PlayTracker', () => {
  it('reports once at 30 s of listening and once more on completion, with one playId', () => {
    const { t, reports } = tracker();
    play(t, 0, 29.5);
    expect(reports).toHaveLength(0);
    play(t, 29.75, 31);
    expect(reports).toHaveLength(1);
    expect(reports[0]).toMatchObject({ playId: 'play-1', trackId: 't1', source: 'ALBUM', sourceId: 'al1', completed: false, skipped: false });
    expect(reports[0].msPlayed).toBeGreaterThanOrEqual(30_000);
    play(t, 31.25, 60);
    t.finish(true);
    t.finish(true);
    expect(reports).toHaveLength(2);
    expect(reports[1]).toMatchObject({ playId: 'play-1', completed: true, skipped: false, msPlayed: 60_000 });
  });

  it('seeking does not count as listening', () => {
    const { t, reports } = tracker();
    play(t, 0, 5);
    t.interrupt();
    play(t, 120, 130);            // jumped ahead: only the 10 s actually heard count
    expect(t.msPlayed).toBe(15_000);
    t.onTime(170);                // a jump without a seeking event is ignored as well
    expect(t.msPlayed).toBe(15_000);
    expect(reports).toHaveLength(0);
  });

  it('moving on reports a skip, but not for a quick flick under a second', () => {
    const quick = tracker();
    play(quick.t, 0, 0.5);
    quick.t.finish(false);
    expect(quick.reports).toHaveLength(0);

    const skipped = tracker();
    play(skipped.t, 0, 12);
    skipped.t.finish(false);
    expect(skipped.reports).toEqual([expect.objectContaining({ skipped: true, completed: false, msPlayed: 12_000 })]);
    play(skipped.t, 12.25, 40);
    expect(skipped.reports).toHaveLength(1);   // finished playbacks stay finished
  });

  it('generates a fresh playId per playback by default', () => {
    const send = vi.fn();
    expect(new PlayTracker('t', 'OTHER', null, send).playId).not.toBe(new PlayTracker('t', 'OTHER', null, send).playId);
  });
});
