import { useEffect, useRef, useState } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router';
import { useAuth } from '../auth/AuthProvider';
import { usePlayerActions } from '../player/PlayerProvider';
import { PlayerBar } from './PlayerBar';
import { QueuePanel } from './QueuePanel';
import { Sidebar } from './Sidebar';

const QUEUE_KEY = 'cadence.queueOpen';

/** Pages render into <Outlet/>; the player bar and queue live here and stay mounted while the route changes. */
export function AppShell() {
  const { status } = useAuth();
  const location = useLocation();
  const player = usePlayerActions();
  const scroller = useRef<HTMLDivElement>(null);
  const [queueOpen, setQueueOpen] = useState(() => {
    try {
      return localStorage.getItem(QUEUE_KEY) === '1';
    } catch {
      return false;
    }
  });

  useEffect(() => {
    try {
      localStorage.setItem(QUEUE_KEY, queueOpen ? '1' : '0');
    } catch {
      // ignore
    }
  }, [queueOpen]);

  useEffect(() => {
    if (scroller.current) scroller.current.scrollTop = 0;
  }, [location.pathname]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement;
      if (e.code === 'Space' && !['INPUT', 'TEXTAREA', 'SELECT', 'BUTTON'].includes(target.tagName) && !target.isContentEditable
          && target.getAttribute('role') !== 'slider') {
        e.preventDefault();
        player.togglePlay();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [player]);

  if (status === 'signed-out') return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  if (status === 'loading') return <Boot />;

  return (
    <div className={`shell${queueOpen ? ' with-queue' : ''}`}>
      <Sidebar />
      <main className="panel main">
        <div className="main-scroll scroll" ref={scroller} id="main-scroll">
          <Outlet />
        </div>
      </main>
      {queueOpen && <QueuePanel onClose={() => setQueueOpen(false)} />}
      <PlayerBar queueOpen={queueOpen} onToggleQueue={() => setQueueOpen((o) => !o)} />
    </div>
  );
}

export function Boot() {
  return (
    <div className="boot" aria-busy="true" aria-label="Loading">
      <span className="brand-mark" style={{ height: 28 }}><i /><i /><i /><i /></span>
    </div>
  );
}

/** Solid top bar once the page has scrolled past its hero. */
export function useScrolled(threshold = 120): boolean {
  const [scrolled, setScrolled] = useState(false);
  useEffect(() => {
    const el = document.getElementById('main-scroll');
    if (!el) return;
    const onScroll = () => setScrolled(el.scrollTop > threshold);
    onScroll();
    el.addEventListener('scroll', onScroll, { passive: true });
    return () => el.removeEventListener('scroll', onScroll);
  }, [threshold]);
  return scrolled;
}
