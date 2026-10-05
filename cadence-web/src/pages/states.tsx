import { Link } from 'react-router';
import { ApiError } from '../api/client';
import { TopBar } from '../components/TopBar';

export function PageSkeleton() {
  return (
    <>
      <TopBar solid={false} />
      <div style={{ display: 'flex', gap: 28, padding: '24px 24px 28px', alignItems: 'flex-end' }} aria-busy="true">
        <div className="skeleton" style={{ width: 232, height: 232 }} />
        <div style={{ flex: 1 }}>
          <div className="skeleton" style={{ width: 80, height: 14 }} />
          <div className="skeleton" style={{ width: '60%', height: 72, margin: '14px 0' }} />
          <div className="skeleton" style={{ width: 240, height: 14 }} />
        </div>
      </div>
      <div style={{ padding: '0 24px' }}>
        {Array.from({ length: 6 }, (_, i) => <div key={i} className="skeleton" style={{ height: 48, margin: '10px 0' }} />)}
      </div>
    </>
  );
}

export function LoadError({ what, error }: { what: string; error: unknown }) {
  const notFound = error instanceof ApiError && error.status === 404;
  return (
    <>
      <TopBar solid={false} />
      <div className="empty">
        <h2>{notFound ? `This ${what} doesn’t exist` : `Couldn’t load this ${what}`}</h2>
        <p>{notFound ? 'It may have been removed, or the link is wrong.' : 'Check your connection and try again.'}</p>
        <Link className="btn btn-ghost" to="/">Go home</Link>
      </div>
    </>
  );
}

export function NotFoundPage() {
  return <LoadError what="page" error={new ApiError({ status: 404 })} />;
}
