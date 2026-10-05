import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/endpoints';
import { useScrolled } from '../components/AppShell';
import { TopBar } from '../components/TopBar';
import { TrackList } from '../components/TrackList';
import { relativeDate } from '../lib/format';
import type { QueueContext } from '../player/queue';

const RANGES = [['short', 'Last 4 weeks'], ['medium', 'Last 6 months'], ['long', 'All time']] as const;
type Range = (typeof RANGES)[number][0];

export function TopTracksPage() {
  const [range, setRange] = useState<Range>('short');
  const scrolled = useScrolled(60);
  const top = useQuery({ queryKey: ['top-tracks', range], queryFn: () => api.topTracks(range) });
  const recent = useQuery({ queryKey: ['recently-played'], queryFn: api.recentlyPlayed });
  const context: QueueContext = { source: 'LIBRARY', sourceId: null, label: 'Your top tracks', href: '/me/top' };

  return (
    <div className="hero-tint" style={{ ['--hue' as string]: 32 }}>
      <TopBar solid={scrolled} />
      <div className="page">
        <h1 className="greeting">Your top tracks</h1>
        <p className="muted" style={{ marginTop: 6 }}>Songs you streamed for at least 30 seconds.</p>
        <div style={{ display: 'flex', gap: 8, marginTop: 18 }} role="tablist">
          {RANGES.map(([value, label]) => (
            <button key={value} type="button" role="tab" aria-selected={range === value} className={`chip${range === value ? ' active' : ''}`}
                    onClick={() => setRange(value)}>{label}</button>
          ))}
        </div>
        <div className="section" style={{ marginTop: 16 }}>
          {top.data && top.data.items.length === 0 && <p className="muted">Nothing yet for this period. Keep listening!</p>}
          {top.data && top.data.items.length > 0 && (
            <TrackList items={top.data.items.map((i) => ({ track: i.track, extra: <span className="tnum">{i.plays} {i.plays === 1 ? 'stream' : 'streams'}</span> }))}
                       context={context} extraHeader="Streams" />
          )}
        </div>
        <section className="section">
          <h2 className="section-title" style={{ marginBottom: 6 }}>Recently played</h2>
          {recent.data && recent.data.items.length === 0 && <p className="muted">Your listening history will show up here.</p>}
          {recent.data && recent.data.items.length > 0 && (
            <TrackList items={recent.data.items.map((i) => ({ track: i.track, playable: i.playable, extra: <span className="faint">{relativeDate(i.playedAt)}</span> }))}
                       context={{ ...context, label: 'Recently played' }} extraHeader="Played" />
          )}
        </section>
      </div>
    </div>
  );
}
