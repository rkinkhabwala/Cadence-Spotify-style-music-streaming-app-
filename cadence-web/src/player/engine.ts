import Hls from 'hls.js/light';

/**
 * One audio element for the whole app session, driven by hls.js (or the browser's native HLS in Safari).
 * Created once by the PlayerProvider, which sits above the router, so page navigation can't touch it.
 */
export class AudioEngine {
  readonly audio: HTMLAudioElement;
  private hls: Hls | null = null;
  /** Number of sources loaded so far (exposed for the browser check: navigation must not reload). */
  loads = 0;

  constructor(audio: HTMLAudioElement = new Audio()) {
    this.audio = audio;
    this.audio.preload = 'auto';
  }

  /** Loads a master playlist and resolves once playback can start; `startAt` seconds resumes mid-track. */
  load(manifestUrl: string, onFatal: (reason: string) => void, startAt = 0) {
    this.detach();
    this.loads++;
    if (Hls.isSupported()) {
      const hls = new Hls({ startPosition: startAt, maxBufferLength: 30, enableWorker: true });
      hls.on(Hls.Events.ERROR, (_event, data) => {
        if (!data.fatal) return;
        if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
          hls.recoverMediaError();
        } else {
          onFatal(`${data.type}: ${data.details}`);
        }
      });
      hls.attachMedia(this.audio);
      hls.loadSource(manifestUrl);
      this.hls = hls;
    } else if (this.audio.canPlayType('application/vnd.apple.mpegurl')) {
      this.audio.src = manifestUrl;
      if (startAt > 0) {
        this.audio.addEventListener('loadedmetadata', () => { this.audio.currentTime = startAt; }, { once: true });
      }
    } else {
      onFatal('This browser cannot play HLS audio');
    }
  }

  play(): Promise<void> {
    return this.audio.play();
  }

  pause() {
    this.audio.pause();
  }

  seek(seconds: number) {
    if (Number.isFinite(seconds)) this.audio.currentTime = Math.max(0, seconds);
  }

  stop() {
    this.audio.pause();
    this.detach();
  }

  private detach() {
    if (this.hls) {
      this.hls.destroy();
      this.hls = null;
    }
    this.audio.removeAttribute('src');
    this.audio.load();
  }
}
