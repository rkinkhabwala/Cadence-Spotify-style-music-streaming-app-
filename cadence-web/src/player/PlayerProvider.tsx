import { createContext, use, useCallback, useEffect, useMemo, useReducer, useRef, useState, type ReactNode } from 'react';
import { api } from '../api/endpoints';
import { ApiError } from '../api/client';
import type { TrackSummary } from '../api/types';
import { AudioEngine } from './engine';
import * as Queue from './queue';
import { PlayTracker } from './tracker';

type Action =
  | { type: 'play'; tracks: TrackSummary[]; index: number; context: Queue.QueueContext }
  | { type: 'next'; auto: boolean }
  | { type: 'previous' }
  | { type: 'shuffle' }
  | { type: 'repeat' }
  | { type: 'enqueue'; track: TrackSummary }
  | { type: 'removeUpNext'; index: number }
  | { type: 'jump'; position: number }
  | { type: 'reset' };

interface Model {
  queue: Queue.QueueState;
  /** Set when the queue ran out; playback stops on the last track. */
  ended: boolean;
}

function reducer(model: Model, action: Action): Model {
  const { queue } = model;
  switch (action.type) {
    case 'play':
      return { queue: Queue.playContext(queue, action.tracks, action.index, action.context), ended: false };
    case 'next': {
      const advance = Queue.next(queue, action.auto);
      return { queue: advance.state, ended: advance.ended };
    }
    case 'previous':
      return { queue: Queue.previous(queue), ended: false };
    case 'shuffle':
      return { ...model, queue: Queue.toggleShuffle(queue) };
    case 'repeat':
      return { ...model, queue: Queue.cycleRepeat(queue) };
    case 'enqueue':
      return { ...model, queue: Queue.enqueue(queue, action.track) };
    case 'removeUpNext':
      return { ...model, queue: Queue.removeFromUpNext(queue, action.index) };
    case 'jump':
      return { queue: Queue.jumpTo(queue, action.position), ended: false };
    case 'reset':
      return { queue: Queue.emptyQueue, ended: false };
  }
}

export interface PlayerState {
  queue: Queue.QueueState;
  current: TrackSummary | null;
  isPlaying: boolean;
  isLoading: boolean;
  error: string | null;
  volume: number;
  muted: boolean;
}

export interface PlayerActions {
  playTracks: (tracks: TrackSummary[], index: number, context: Queue.QueueContext) => void;
  togglePlay: () => void;
  next: () => void;
  previous: () => void;
  seek: (seconds: number) => void;
  setVolume: (volume: number) => void;
  toggleMute: () => void;
  toggleShuffle: () => void;
  cycleRepeat: () => void;
  enqueue: (track: TrackSummary) => void;
  removeFromUpNext: (index: number) => void;
  jumpTo: (position: number) => void;
  stop: () => void;
}

export interface Progress {
  position: number;
  duration: number;
  buffered: number;
}

const StateContext = createContext<PlayerState | null>(null);
const ActionsContext = createContext<PlayerActions | null>(null);
const ProgressContext = createContext<Progress>({ position: 0, duration: 0, buffered: 0 });

export function usePlayer(): PlayerState {
  const value = use(StateContext);
  if (!value) throw new Error('usePlayer outside PlayerProvider');
  return value;
}

export function usePlayerActions(): PlayerActions {
  const value = use(ActionsContext);
  if (!value) throw new Error('usePlayerActions outside PlayerProvider');
  return value;
}

/** Separate context: only progress-aware components re-render on every time update. */
export function usePlayerProgress(): Progress {
  return use(ProgressContext);
}

const VOLUME_KEY = 'cadence.volume';
const playableOnly = (tracks: TrackSummary[]) => tracks.filter((t) => t.status === 'READY');

declare global {
  interface Window {
    cadencePlayer?: { audio: HTMLAudioElement; loads: () => number; current: () => string | null };
  }
}

export function PlayerProvider({ children, engine: injected }: { children: ReactNode; engine?: AudioEngine }) {
  const engineRef = useRef<AudioEngine | null>(null);
  if (engineRef.current === null) engineRef.current = injected ?? new AudioEngine();
  const engine = engineRef.current;

  const [model, dispatch] = useReducer(reducer, { queue: Queue.emptyQueue, ended: false });
  const [isPlaying, setPlaying] = useState(false);
  const [isLoading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [progress, setProgress] = useState<Progress>({ position: 0, duration: 0, buffered: 0 });
  const [volume, setVolumeState] = useState(() => {
    const stored = Number(globalThis.localStorage?.getItem(VOLUME_KEY));
    return Number.isFinite(stored) && stored > 0 && stored <= 1 ? stored : 0.8;
  });
  const [muted, setMuted] = useState(false);
  const tracker = useRef<PlayTracker | null>(null);
  const loadSerial = useRef(0);
  const current = model.queue.current;

  // ---- audio element events → state
  useEffect(() => {
    const audio = engine.audio;
    const time = () => {
      tracker.current?.onTime(audio.currentTime);
      const end = audio.buffered.length ? audio.buffered.end(audio.buffered.length - 1) : 0;
      setProgress({ position: audio.currentTime, duration: Number.isFinite(audio.duration) ? audio.duration : 0, buffered: end });
    };
    const onPlay = () => setPlaying(true);
    const onPause = () => {
      setPlaying(false);
      tracker.current?.interrupt();
    };
    const onSeeking = () => tracker.current?.interrupt();
    const onWaiting = () => setLoading(true);
    const onPlaying = () => setLoading(false);
    const onEnded = () => {
      tracker.current?.finish(true);
      dispatch({ type: 'next', auto: true });
    };
    const listeners: [string, () => void][] = [['timeupdate', time], ['durationchange', time], ['progress', time],
      ['play', onPlay], ['pause', onPause], ['seeking', onSeeking], ['waiting', onWaiting], ['playing', onPlaying],
      ['canplay', onPlaying], ['ended', onEnded]];
    listeners.forEach(([event, fn]) => audio.addEventListener(event, fn));
    window.cadencePlayer = { audio, loads: () => engine.loads, current: () => tracker.current ? engine.audio.currentSrc || 'hls' : null };
    return () => listeners.forEach(([event, fn]) => audio.removeEventListener(event, fn));
  }, [engine]);

  useEffect(() => {
    engine.audio.volume = volume;
    engine.audio.muted = muted;
    try {
      localStorage.setItem(VOLUME_KEY, String(volume));
    } catch {
      // ignore
    }
  }, [engine, volume, muted]);

  // ---- load whenever a new playback starts (a new serial), never on navigation
  const report = useCallback((r: Parameters<typeof api.reportPlay>[0]) => {
    api.reportPlay(r).catch(() => undefined); // best effort; the next report carries the full state anyway
  }, []);

  useEffect(() => {
    if (!current) return;
    const serial = ++loadSerial.current;
    const context = model.queue.context;
    const recommended = !current.fromUpNext && context?.recommendationId
      ? { recommendationId: context.recommendationId, position: context.positions?.[current.track.id] }
      : {};
    tracker.current = new PlayTracker(current.track.id, context?.source ?? 'OTHER', context?.sourceId ?? null, report,
      undefined, recommended);
    setError(null);
    setLoading(true);
    setProgress({ position: 0, duration: (current.track.durationMs ?? 0) / 1000, buffered: 0 });

    let attempts = 0;
    const start = (startAt: number) => {
      api.startPlayback(current.track.id).then((playback) => {
        if (serial !== loadSerial.current) return;
        engine.load(playback.manifestUrl, (reason) => {
          // segment URLs expire with the session: get a fresh manifest once and resume where we were
          if (serial === loadSerial.current && attempts++ < 1) start(engine.audio.currentTime);
          else if (serial === loadSerial.current) fail(`Playback failed (${reason})`);
        }, startAt);
        engine.play().catch((e: unknown) => {
          if (serial === loadSerial.current && e instanceof DOMException && e.name === 'NotAllowedError') {
            setLoading(false); // autoplay blocked until the user presses play
          }
        });
      }).catch((e: unknown) => {
        if (serial !== loadSerial.current) return;
        fail(e instanceof ApiError ? e.message : 'Could not start playback');
      });
    };
    const fail = (message: string) => {
      setError(message);
      setLoading(false);
      setPlaying(false);
    };
    start(0);
  }, [current?.serial]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (model.ended) {
      engine.pause();
      engine.seek(0);
    }
  }, [engine, model.ended]);

  // ---- OS media controls
  useEffect(() => {
    if (!('mediaSession' in navigator) || !current) return;
    const t = current.track;
    navigator.mediaSession.metadata = new MediaMetadata({
      title: t.title,
      artist: t.artists.map((a) => a.name).filter(Boolean).join(', '),
      album: t.album?.title ?? '',
      artwork: t.album?.coverUrl ? [{ src: t.album.coverUrl, sizes: '512x512' }] : [],
    });
  }, [current]);

  const actions = useMemo<PlayerActions>(() => {
    const moveOn = () => tracker.current?.finish(false);
    return {
      playTracks: (tracks, index, context) => {
        const playable = playableOnly(tracks);
        const target = tracks[index];
        const start = Math.max(0, playable.findIndex((t) => t.id === target?.id));
        if (playable.length === 0) return;
        moveOn();
        dispatch({ type: 'play', tracks: playable, index: start, context });
      },
      togglePlay: () => {
        if (!engineRef.current) return;
        const audio = engine.audio;
        if (audio.paused) engine.play().catch(() => undefined);
        else engine.pause();
      },
      next: () => {
        moveOn();
        dispatch({ type: 'next', auto: false });
      },
      previous: () => {
        if (engine.audio.currentTime > 3) {
          engine.seek(0);
          return;
        }
        moveOn();
        dispatch({ type: 'previous' });
      },
      seek: (seconds) => engine.seek(seconds),
      setVolume: (v) => {
        setVolumeState(Math.min(1, Math.max(0, v)));
        setMuted(false);
      },
      toggleMute: () => setMuted((m) => !m),
      toggleShuffle: () => dispatch({ type: 'shuffle' }),
      cycleRepeat: () => dispatch({ type: 'repeat' }),
      enqueue: (track) => dispatch({ type: 'enqueue', track }),
      removeFromUpNext: (index) => dispatch({ type: 'removeUpNext', index }),
      jumpTo: (position) => {
        moveOn();
        dispatch({ type: 'jump', position });
      },
      stop: () => {
        moveOn();
        tracker.current = null;
        loadSerial.current++;
        engine.stop();
        dispatch({ type: 'reset' });
      },
    };
  }, [engine]);

  useEffect(() => {
    if (!('mediaSession' in navigator)) return;
    const handlers: [MediaSessionAction, MediaSessionActionHandler][] = [
      ['play', () => actions.togglePlay()], ['pause', () => actions.togglePlay()],
      ['nexttrack', () => actions.next()], ['previoustrack', () => actions.previous()],
      ['seekto', (d) => d.seekTime !== undefined && actions.seek(d.seekTime)],
    ];
    handlers.forEach(([action, handler]) => {
      try {
        navigator.mediaSession.setActionHandler(action, handler);
      } catch {
        // unsupported action
      }
    });
  }, [actions]);

  const state = useMemo<PlayerState>(() => ({
    queue: model.queue, current: current?.track ?? null, isPlaying, isLoading, error, volume, muted,
  }), [model.queue, current, isPlaying, isLoading, error, volume, muted]);

  return (
    <ActionsContext value={actions}>
      <StateContext value={state}>
        <ProgressContext value={progress}>{children}</ProgressContext>
      </StateContext>
    </ActionsContext>
  );
}
