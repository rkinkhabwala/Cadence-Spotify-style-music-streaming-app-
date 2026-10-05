// hls.js/light (no subtitles, DRM or alternate audio, none of which audio-only HLS needs) has the same API as the
// full build but ships without its own declaration file.
declare module 'hls.js/light' {
  import Hls from 'hls.js';
  export * from 'hls.js';
  export default Hls;
}
