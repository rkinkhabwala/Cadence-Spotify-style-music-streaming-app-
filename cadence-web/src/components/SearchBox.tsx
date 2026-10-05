import { useEffect, useId, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { api } from '../api/endpoints';
import type { Suggestion } from '../api/types';
import { Artwork } from './Artwork';
import { CloseIcon, SearchIcon } from './Icons';

const DEBOUNCE_MS = 120;

export function hrefOf(s: Pick<Suggestion, 'type' | 'id'>, albumId?: string | null): string {
  switch (s.type) {
    case 'artist': return `/artist/${s.id}`;
    case 'album': return `/album/${s.id}`;
    case 'playlist': return `/playlist/${s.id}`;
    case 'track': return albumId ? `/album/${albumId}` : `/search?q=`;
  }
}

/** Highlights the typed prefix in a suggestion. */
function Highlight({ text, query }: { text: string; query: string }) {
  const at = text.toLowerCase().indexOf(query.toLowerCase());
  if (!query || at < 0) return <>{text}</>;
  return <>{text.slice(0, at)}<mark>{text.slice(at, at + query.length)}</mark>{text.slice(at + query.length)}</>;
}

/**
 * Search field with search-as-you-type suggestions (debounced, previous request aborted). Enter runs a full search,
 * arrow keys move through suggestions, Escape closes them.
 */
export function SearchBox({ value, onChange, onSubmit, autoFocus }: {
  value: string; onChange: (value: string) => void; onSubmit: (value: string) => void; autoFocus?: boolean;
}) {
  const navigate = useNavigate();
  const [items, setItems] = useState<Suggestion[]>([]);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const listId = useId();
  const box = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const text = value.trim();
    if (!text) {
      setItems([]);
      return;
    }
    const controller = new AbortController();
    const timer = setTimeout(() => {
      api.suggest(text, controller.signal).then((r) => {
        setItems(r.items);
        setActive(-1);
      }).catch(() => undefined);
    }, DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [value]);

  useEffect(() => {
    const close = (e: MouseEvent) => !box.current?.contains(e.target as Node) && setOpen(false);
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, []);

  const go = (s: Suggestion) => {
    setOpen(false);
    if (s.type === 'track') onSubmit(s.text);
    else navigate(hrefOf(s));
  };

  const showList = open && value.trim().length > 0 && items.length > 0;
  return (
    <div className="search-box" ref={box}>
      <SearchIcon />
      <input
        className="search-input"
        type="search"
        role="combobox"
        aria-label="What do you want to play?"
        aria-expanded={showList}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={active >= 0 ? `${listId}-${active}` : undefined}
        placeholder="What do you want to play?"
        value={value}
        autoFocus={autoFocus}
        spellCheck={false}
        onChange={(e) => { onChange(e.target.value); setOpen(true); }}
        onFocus={() => setOpen(true)}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown') {
            e.preventDefault();
            setOpen(true);
            setActive((a) => Math.min(items.length - 1, a + 1));
          } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            setActive((a) => Math.max(-1, a - 1));
          } else if (e.key === 'Enter') {
            if (showList && active >= 0) go(items[active]);
            else {
              setOpen(false);
              onSubmit(value.trim());
            }
          } else if (e.key === 'Escape') {
            setOpen(false);
          }
        }}
      />
      {value && (
        <button type="button" className="icon-btn search-clear" aria-label="Clear search" onClick={() => { onChange(''); onSubmit(''); }}>
          <CloseIcon />
        </button>
      )}
      {showList && (
        <div className="suggest" role="listbox" id={listId} aria-label="Suggestions">
          {items.map((s, i) => (
            <div key={`${s.type}-${s.id}`} id={`${listId}-${i}`} role="option" aria-selected={i === active}
                 className={`suggest-item${i === active ? ' active' : ''}`}
                 onMouseEnter={() => setActive(i)} onMouseDown={(e) => { e.preventDefault(); go(s); }}>
              <Artwork seed={s.id} title={s.text} src={s.imageUrl} round={s.type === 'artist'} />
              <div style={{ minWidth: 0 }}>
                <div className="ellipsis" style={{ fontWeight: 600 }}><Highlight text={s.text} query={value.trim()} /></div>
                {s.subtitle && <div className="ellipsis faint" style={{ fontSize: 13 }}>{s.subtitle}</div>}
              </div>
              <span className="suggest-type">{s.type}</span>
            </div>
          ))}
          <div className="suggest-foot">Press Enter to see all results for “{value.trim()}”</div>
        </div>
      )}
    </div>
  );
}
