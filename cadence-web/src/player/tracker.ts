import type { PlaySource } from '../api/types';

export interface PlayReport {
  playId: string;
  trackId: string;
  msPlayed: number;
  source: PlaySource;
  sourceId: string | null;
  completed: boolean;
  skipped: boolean;
}

/** Playback reports shorter than this are not sent when the user moves on (a quick flick through tracks). */
export const MIN_SKIP_REPORT_MS = 1_000;
export const STREAM_MS = 30_000;
/** A larger jump between two time updates is a seek, not listening. */
const MAX_TICK_SECONDS = 1.5;

/**
 * Counts the milliseconds actually listened during one playback (seeks and pauses don't count) and produces the
 * reports the API expects (spec 3.2): one when 30 s are reached, and a final one when the track completes or the user
 * moves on (skipped). All reports share a playId, which the API uses to merge them, so a play is counted once.
 */
export class PlayTracker {
  readonly playId: string;
  private listenedMs = 0;
  private lastTime: number | null = null;
  private streamReported = false;
  private finished = false;

  constructor(
    private readonly trackId: string,
    private readonly source: PlaySource,
    private readonly sourceId: string | null,
    private readonly send: (report: PlayReport) => void,
    playId: string = crypto.randomUUID(),
  ) {
    this.playId = playId;
  }

  get msPlayed(): number {
    return Math.round(this.listenedMs);
  }

  /** Called on every `timeupdate` while playing. */
  onTime(currentTime: number) {
    if (this.finished) return;
    if (this.lastTime !== null) {
      const delta = currentTime - this.lastTime;
      if (delta > 0 && delta <= MAX_TICK_SECONDS) this.listenedMs += delta * 1000;
    }
    this.lastTime = currentTime;
    if (!this.streamReported && this.listenedMs >= STREAM_MS) {
      this.streamReported = true;
      this.emit(false, false);
    }
  }

  /** Pauses and seeks break the listening streak (the next tick only sets the baseline). */
  interrupt() {
    this.lastTime = null;
  }

  /** The track ended by itself (`completed`) or the user moved on. Only the first call reports. */
  finish(completed: boolean) {
    if (this.finished) return;
    this.finished = true;
    if (completed) this.emit(true, false);
    else if (this.listenedMs >= MIN_SKIP_REPORT_MS) this.emit(false, true);
  }

  private emit(completed: boolean, skipped: boolean) {
    this.send({
      playId: this.playId, trackId: this.trackId, msPlayed: this.msPlayed, source: this.source, sourceId: this.sourceId,
      completed, skipped,
    });
  }
}
