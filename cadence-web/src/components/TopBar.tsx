import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router';
import { useAuth } from '../auth/AuthProvider';
import { ChartIcon, ChevronLeftIcon, ChevronRightIcon, LogoutIcon } from './Icons';

export function TopBar({ solid, children }: { solid: boolean; children?: ReactNode }) {
  const navigate = useNavigate();
  const { profile, logout } = useAuth();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => !ref.current?.contains(e.target as Node) && setOpen(false);
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [open]);

  const idx = (window.history.state as { idx?: number } | null)?.idx ?? 0;
  return (
    <header className={`topbar${solid ? ' solid' : ''}`}>
      <button type="button" className="history-btn" aria-label="Go back" disabled={idx === 0} onClick={() => navigate(-1)}>
        <ChevronLeftIcon />
      </button>
      <button type="button" className="history-btn" aria-label="Go forward" onClick={() => navigate(1)}>
        <ChevronRightIcon />
      </button>
      {children}
      <div className="spacer" />
      <div ref={ref} style={{ position: 'relative' }}>
        <button type="button" className="user-chip" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
          <span className="avatar">{(profile?.displayName ?? '?')[0].toUpperCase()}</span>
          <span className="ellipsis" style={{ maxWidth: 160 }}>{profile?.displayName}</span>
        </button>
        {open && (
          <div className="menu" role="menu">
            <div className="menu-label">{profile?.email} · {profile?.plan === 'PREMIUM' ? 'Premium' : 'Free'}</div>
            <Link role="menuitem" to="/me/top" onClick={() => setOpen(false)}><ChartIcon width={18} />Your top tracks</Link>
            <hr />
            <button role="menuitem" onClick={() => { setOpen(false); void logout(); }}><LogoutIcon width={18} />Log out</button>
          </div>
        )}
      </div>
    </header>
  );
}
