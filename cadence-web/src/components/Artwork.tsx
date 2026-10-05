import { useState } from 'react';
import { hueOf } from '../lib/color';

interface Props {
  seed: string;
  title: string;
  src?: string | null;
  round?: boolean;
  size?: number;
  className?: string;
}

/**
 * Cover art. The catalog has no images yet, so missing covers are generated from the entity id: a gradient in a
 * stable hue, film grain, and the title's initial set large in the display face, unique per album or artist and
 * consistent wherever it appears.
 */
export function Artwork({ seed, title, src, round, size, className }: Props) {
  const [failed, setFailed] = useState(false);
  const hue = hueOf(seed);
  const style = size ? { width: size } : undefined;
  const classes = ['art', round ? 'round' : '', className ?? ''].filter(Boolean).join(' ');
  if (src && !failed) {
    return (
      <div className={classes} style={style}>
        <img src={src} alt="" loading="lazy" onError={() => setFailed(true)} />
      </div>
    );
  }
  const glyph = (title.trim()[0] ?? '♪').toUpperCase();
  const shift = (hue * 7) % 60;
  const background = [
    `radial-gradient(120% 90% at ${20 + (hue % 50)}% 0%, hsl(${hue} 85% 68% / 0.9), transparent 55%)`,
    `radial-gradient(90% 80% at 100% 100%, hsl(${(hue + 40 + shift) % 360} 70% 40%), transparent 70%)`,
    `linear-gradient(${135 + (hue % 90)}deg, hsl(${hue} 62% 46%), hsl(${(hue + 50) % 360} 58% 22%))`,
  ].join(',');
  return (
    <div className={classes} style={{ ...style, background }} aria-hidden="true">
      <div className="art-grain" />
      <div className="art-gen" style={{ ['--glyph' as string]: size ? `${Math.round(size * 0.62)}px` : '62cqw' }}>
        <span>{glyph}</span>
      </div>
    </div>
  );
}
