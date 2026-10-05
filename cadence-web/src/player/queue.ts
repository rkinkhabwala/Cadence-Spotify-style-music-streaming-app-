import type { PlaySource, TrackSummary } from '../api/types';

/**
 * The play queue as a pure state machine, so shuffle/repeat/up-next behaviour is unit tested without audio.
 *
 * A queue has a *context* (the album, playlist, artist, search results, ... the user started playing from) with its
 * tracks in original order, a play `order` over them (natural or shuffled) and a `cursor` into that order. Tracks the
 * user adds with "Add to queue" go to `upNext` and play before the context continues, as in Spotify.
 */

export type RepeatMode = 'off' | 'all' | 'one';

export interface QueueContext {
  source: PlaySource;
  sourceId: string | null;
  /** What the queue panel says the music is "playing from". */
  label: string;
  href?: string;
}

export interface NowPlaying {
  track: TrackSummary;
  fromUpNext: boolean;
  /** Increments on every (re)start, so restarting the same track is a new playback. */
  serial: number;
}

export interface QueueState {
  tracks: TrackSummary[];
  order: number[];
  cursor: number;
  upNext: TrackSummary[];
  current: NowPlaying | null;
  shuffle: boolean;
  repeat: RepeatMode;
  context: QueueContext | null;
}

export const emptyQueue: QueueState = {
  tracks: [], order: [], cursor: -1, upNext: [], current: null, shuffle: false, repeat: 'off', context: null,
};

export type Random = () => number;

function shuffled(values: number[], random: Random): number[] {
  const result = [...values];
  for (let i = result.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [result[i], result[j]] = [result[j], result[i]];
  }
  return result;
}

function range(n: number): number[] {
  return Array.from({ length: n }, (_, i) => i);
}

/** Natural order, or the start track first followed by the rest in random order. */
function orderFor(count: number, start: number, shuffle: boolean, random: Random): number[] {
  if (!shuffle) return range(count);
  return [start, ...shuffled(range(count).filter((i) => i !== start), random)];
}

function playing(state: QueueState, track: TrackSummary, fromUpNext: boolean): NowPlaying {
  return { track, fromUpNext, serial: (state.current?.serial ?? 0) + 1 };
}

export function playContext(state: QueueState, tracks: TrackSummary[], startIndex: number, context: QueueContext,
                            random: Random = Math.random): QueueState {
  if (tracks.length === 0) return state;
  const start = Math.min(Math.max(startIndex, 0), tracks.length - 1);
  const order = orderFor(tracks.length, start, state.shuffle, random);
  return {
    ...state, tracks, order, cursor: state.shuffle ? 0 : start, context,
    current: playing(state, tracks[start], false),
  };
}

export interface Advance {
  state: QueueState;
  /** True when the queue ran out (repeat off): playback should stop. */
  ended: boolean;
}

/**
 * Moves to the next track. `auto` = the current track finished by itself, which is the only case where
 * repeat-one replays the same track (pressing "next" always moves on, as in Spotify).
 */
export function next(state: QueueState, auto = false, random: Random = Math.random): Advance {
  if (!state.current) return { state, ended: true };
  if (auto && state.repeat === 'one') {
    return { state: { ...state, current: playing(state, state.current.track, state.current.fromUpNext) }, ended: false };
  }
  if (state.upNext.length > 0) {
    const [head, ...rest] = state.upNext;
    return { state: { ...state, upNext: rest, current: playing(state, head, true) }, ended: false };
  }
  if (state.cursor + 1 < state.order.length) {
    const cursor = state.cursor + 1;
    return { state: { ...state, cursor, current: playing(state, state.tracks[state.order[cursor]], false) }, ended: false };
  }
  if (state.repeat !== 'off' && state.order.length > 0) {
    const order = state.shuffle ? shuffled(range(state.tracks.length), random) : state.order;
    return { state: { ...state, order, cursor: 0, current: playing(state, state.tracks[order[0]], false) }, ended: false };
  }
  return { state, ended: true };
}

/** Back to the previous context track (wrapping with repeat-all), or restart the first one. */
export function previous(state: QueueState): QueueState {
  if (!state.current) return state;
  if (state.current.fromUpNext && state.cursor >= 0) {
    return { ...state, current: playing(state, state.tracks[state.order[state.cursor]], false) };
  }
  if (state.cursor > 0) {
    const cursor = state.cursor - 1;
    return { ...state, cursor, current: playing(state, state.tracks[state.order[cursor]], false) };
  }
  if (state.repeat === 'all' && state.order.length > 1) {
    const cursor = state.order.length - 1;
    return { ...state, cursor, current: playing(state, state.tracks[state.order[cursor]], false) };
  }
  return { ...state, current: playing(state, state.current.track, state.current.fromUpNext) };
}

/** Shuffle keeps the current track playing: on, it leads a new random order; off, the natural order resumes there. */
export function toggleShuffle(state: QueueState, random: Random = Math.random): QueueState {
  const shuffle = !state.shuffle;
  if (state.tracks.length === 0) return { ...state, shuffle };
  const currentIndex = state.cursor >= 0 ? state.order[state.cursor] : 0;
  const order = orderFor(state.tracks.length, currentIndex, shuffle, random);
  return { ...state, shuffle, order, cursor: shuffle ? 0 : currentIndex };
}

export function cycleRepeat(state: QueueState): QueueState {
  const repeat: RepeatMode = state.repeat === 'off' ? 'all' : state.repeat === 'all' ? 'one' : 'off';
  return { ...state, repeat };
}

export function enqueue(state: QueueState, track: TrackSummary): QueueState {
  if (!state.current) {
    return {
      ...state, tracks: [track], order: [0], cursor: 0, context: { source: 'OTHER', sourceId: null, label: 'Your queue' },
      current: playing(state, track, false),
    };
  }
  return { ...state, upNext: [...state.upNext, track] };
}

export function removeFromUpNext(state: QueueState, index: number): QueueState {
  return { ...state, upNext: state.upNext.filter((_, i) => i !== index) };
}

/** Jumps to the n-th upcoming context track (as listed by {@link upcoming} after the up-next items). */
export function jumpTo(state: QueueState, orderPosition: number): QueueState {
  if (orderPosition < 0 || orderPosition >= state.order.length) return state;
  return { ...state, cursor: orderPosition, current: playing(state, state.tracks[state.order[orderPosition]], false) };
}

/** What plays after the current track: the up-next items, then the rest of the context in play order. */
export function upcoming(state: QueueState): { upNext: TrackSummary[]; context: { track: TrackSummary; position: number }[] } {
  const context = state.order.slice(state.cursor + 1).map((index, i) => ({
    track: state.tracks[index], position: state.cursor + 1 + i,
  }));
  return { upNext: state.upNext, context };
}
