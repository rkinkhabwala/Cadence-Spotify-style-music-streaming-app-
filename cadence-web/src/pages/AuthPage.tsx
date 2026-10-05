import { useState, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router';
import { ApiError } from '../api/client';
import { useAuth } from '../auth/AuthProvider';
import { Brand } from '../components/Sidebar';

function messageOf(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'invalid-credentials': return 'That email and password don’t match.';
      case 'email-taken': return 'An account with this email already exists. Log in instead?';
      case 'weak-password': return 'Use at least 10 characters for your password.';
      case 'rate-limited': return 'Too many attempts. Wait a minute and try again.';
      case 'validation-failed': return error.problem.errors?.map((e) => `${e.field}: ${e.message}`).join(' · ') ?? error.message;
      default: return error.message;
    }
  }
  return 'Cadence is not reachable right now. Is the API running?';
}

export function AuthPage({ mode }: { mode: 'login' | 'register' }) {
  const { status, login, register } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const from = (location.state as { from?: string } | null)?.from ?? '/';

  if (status === 'signed-in') return <Navigate to={from} replace />;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (mode === 'login') await login(email, password);
      else await register(email, password, name);
      navigate(from, { replace: true });
    } catch (err) {
      setError(messageOf(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="auth">
      <div className="auth-art">
        <Brand />
        <h1 className="auth-headline">
          Every song
          <em>finds its</em>
          cadence.
        </h1>
        <p className="auth-foot">Stream in adaptive HLS, build playlists, and pick up exactly where you left off.</p>
      </div>
      <div className="auth-form-wrap">
        <form className="auth-form" onSubmit={submit} noValidate>
          <h1>{mode === 'login' ? 'Log in to Cadence' : 'Sign up for free'}</h1>
          {error && <div className="form-error" role="alert">{error}</div>}
          {mode === 'register' && (
            <div className="field">
              <label htmlFor="name">What should we call you?</label>
              <input id="name" className="input" value={name} onChange={(e) => setName(e.target.value)} autoComplete="nickname" required />
            </div>
          )}
          <div className="field">
            <label htmlFor="email">Email address</label>
            <input id="email" className="input" type="email" value={email} onChange={(e) => setEmail(e.target.value)}
                   autoComplete="email" required autoFocus />
          </div>
          <div className="field">
            <label htmlFor="password">Password</label>
            <input id="password" className="input" type="password" value={password} onChange={(e) => setPassword(e.target.value)}
                   autoComplete={mode === 'login' ? 'current-password' : 'new-password'} required minLength={mode === 'register' ? 10 : undefined} />
            {mode === 'register' && <span className="field-hint">At least 10 characters.</span>}
          </div>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <span className="spinner" /> : mode === 'login' ? 'Log in' : 'Create account'}
          </button>
          <p className="auth-switch">
            {mode === 'login'
              ? <>New to Cadence? <Link to="/register" state={location.state}>Sign up</Link></>
              : <>Already have an account? <Link to="/login" state={location.state}>Log in</Link></>}
          </p>
        </form>
      </div>
    </div>
  );
}
