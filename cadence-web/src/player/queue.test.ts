import { describe, expect, it } from 'vitest';
import { track } from '../test/fixtures';
import * as Q from './queue';

const ctx: Q.QueueContext = { source: 'ALBUM', sourceId: 'al1', label: 'Night Ferries' };
const tracks = [track(1), track(2), track(3), track(4)];
const ids = (s: Q.QueueState) => s.current?.track.id;
/** Deterministic "random": always picks index 0 in Fisher–Yates → reversal-like permutation. */
const zero = () => 0;

describe('queue', () => {
  it('plays a context from the chosen track and advances in order', () => {
    let s = Q.playContext(Q.emptyQueue, tracks, 1, ctx);
    expect(ids(s)).toBe('t2');
    s = Q.next(s).state;
    expect(ids(s)).toBe('t3');
    s = Q.next(Q.next(s).state).state;
    expect(ids(s)).toBe('t4');
    expect(Q.next(s).ended).toBe(true);
  });

  it('repeat all wraps around and repeat one replays only when a track ends by itself', () => {
    let s = Q.cycleRepeat(Q.playContext(Q.emptyQueue, tracks, 3, ctx));
    expect(s.repeat).toBe('all');
    expect(ids(Q.next(s).state)).toBe('t1');
    s = Q.cycleRepeat(s);
    expect(s.repeat).toBe('one');
    const replay = Q.next(s, true).state;
    expect(ids(replay)).toBe('t4');
    expect(replay.current!.serial).toBeGreaterThan(s.current!.serial);
    expect(ids(Q.next(s, false).state)).toBe('t1');
    expect(Q.cycleRepeat(s).repeat).toBe('off');
  });

  it('previous goes back, wraps with repeat all, and restarts the first track otherwise', () => {
    const s = Q.playContext(Q.emptyQueue, tracks, 2, ctx);
    expect(ids(Q.previous(s))).toBe('t2');
    const first = Q.playContext(Q.emptyQueue, tracks, 0, ctx);
    const restarted = Q.previous(first);
    expect(ids(restarted)).toBe('t1');
    expect(restarted.current!.serial).toBe(first.current!.serial + 1);
    expect(ids(Q.previous(Q.cycleRepeat(first)))).toBe('t4');
  });

  it('shuffle keeps the current track and plays every other track exactly once', () => {
    let s = Q.playContext(Q.emptyQueue, tracks, 2, ctx);
    s = Q.toggleShuffle(s, zero);
    expect(ids(s)).toBe('t3');
    expect(s.order[0]).toBe(2);
    const played = ['t3'];
    let advance = Q.next(s);
    while (!advance.ended) {
      played.push(ids(advance.state)!);
      advance = Q.next(advance.state);
    }
    expect([...played].sort()).toEqual(['t1', 't2', 't3', 't4']);
    // turning shuffle off resumes natural order after the current track
    const off = Q.toggleShuffle(s);
    expect(off.cursor).toBe(2);
    expect(ids(Q.next(off).state)).toBe('t4');
  });

  it('starting a context while shuffled puts the chosen track first', () => {
    const shuffled = Q.toggleShuffle(Q.emptyQueue);
    const s = Q.playContext(shuffled, tracks, 3, ctx, zero);
    expect(ids(s)).toBe('t4');
    expect(s.order).toHaveLength(4);
  });

  it('up-next tracks play before the context continues, and previous returns to the context', () => {
    let s = Q.playContext(Q.emptyQueue, tracks, 0, ctx);
    s = Q.enqueue(Q.enqueue(s, track(9)), track(8));
    expect(Q.upcoming(s).upNext.map((t) => t.id)).toEqual(['t9', 't8']);
    s = Q.next(s).state;
    expect(ids(s)).toBe('t9');
    expect(s.current!.fromUpNext).toBe(true);
    expect(ids(Q.previous(s))).toBe('t1');
    s = Q.removeFromUpNext(s, 0);
    expect(s.upNext).toEqual([]);
    expect(ids(Q.next(s).state)).toBe('t2');
  });

  it('enqueueing with nothing playing starts playback', () => {
    const s = Q.enqueue(Q.emptyQueue, track(5));
    expect(ids(s)).toBe('t5');
    expect(s.context?.label).toBe('Your queue');
  });

  it('jumps to an upcoming context track', () => {
    const s = Q.jumpTo(Q.playContext(Q.emptyQueue, tracks, 0, ctx), 3);
    expect(ids(s)).toBe('t4');
    expect(Q.upcoming(s).context).toEqual([]);
    expect(Q.jumpTo(s, 99)).toBe(s);
  });
});
