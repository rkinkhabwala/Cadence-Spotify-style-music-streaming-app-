import { useRef, useState, type KeyboardEvent, type PointerEvent } from 'react';

interface Props {
  value: number;
  max: number;
  buffered?: number;
  step?: number;
  label: string;
  valueText?: string;
  /** Called continuously while dragging (preview) */
  onPreview?: (value: number | null) => void;
  onCommit: (value: number) => void;
}

/** Accessible range slider (pointer + keyboard) styled like the rest of the player. */
export function Slider({ value, max, buffered = 0, step = 5, label, valueText, onPreview, onCommit }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const [drag, setDrag] = useState<number | null>(null);
  const shown = drag ?? value;
  const pct = max > 0 ? Math.min(100, (shown / max) * 100) : 0;
  const buf = max > 0 ? Math.min(100, (buffered / max) * 100) : 0;

  const at = (clientX: number) => {
    const rect = ref.current!.getBoundingClientRect();
    return Math.min(max, Math.max(0, ((clientX - rect.left) / rect.width) * max));
  };
  const down = (e: PointerEvent<HTMLDivElement>) => {
    if (max <= 0) return;
    e.currentTarget.setPointerCapture(e.pointerId);
    const v = at(e.clientX);
    setDrag(v);
    onPreview?.(v);
  };
  const move = (e: PointerEvent<HTMLDivElement>) => {
    if (drag === null) return;
    const v = at(e.clientX);
    setDrag(v);
    onPreview?.(v);
  };
  const up = (e: PointerEvent<HTMLDivElement>) => {
    if (drag === null) return;
    const v = at(e.clientX);
    setDrag(null);
    onPreview?.(null);
    onCommit(v);
  };
  const key = (e: KeyboardEvent<HTMLDivElement>) => {
    const delta = e.key === 'ArrowRight' || e.key === 'ArrowUp' ? step : e.key === 'ArrowLeft' || e.key === 'ArrowDown' ? -step : 0;
    if (delta !== 0) {
      e.preventDefault();
      onCommit(Math.min(max, Math.max(0, value + delta)));
    }
  };

  return (
    <div
      ref={ref}
      className={`slider${drag !== null ? ' dragging' : ''}`}
      style={{ ['--pct' as string]: `${pct}%`, ['--buf' as string]: `${buf}%` }}
      role="slider"
      tabIndex={0}
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={Math.round(max)}
      aria-valuenow={Math.round(shown)}
      aria-valuetext={valueText}
      onPointerDown={down}
      onPointerMove={move}
      onPointerUp={up}
      onPointerCancel={() => { setDrag(null); onPreview?.(null); }}
      onKeyDown={key}
    >
      <div className="slider-track">
        <div className="slider-buf" />
        <div className="slider-fill" />
      </div>
      <div className="slider-thumb" />
    </div>
  );
}
