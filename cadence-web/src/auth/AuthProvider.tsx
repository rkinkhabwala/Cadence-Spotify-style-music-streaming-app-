import { createContext, use, useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { clearTokens, hasSession, onAuthChange, setTokens } from '../api/client';
import { api } from '../api/endpoints';
import type { Profile } from '../api/types';

type Status = 'loading' | 'signed-out' | 'signed-in';

interface AuthValue {
  status: Status;
  profile: Profile | null;
  isAdmin: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string, displayName: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthValue | null>(null);

export function useAuth(): AuthValue {
  const value = use(AuthContext);
  if (!value) throw new Error('useAuth outside AuthProvider');
  return value;
}

export function AuthProvider({ children, onSignOut }: { children: ReactNode; onSignOut?: () => void }) {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<Status>(hasSession() ? 'loading' : 'signed-out');
  const [profile, setProfile] = useState<Profile | null>(null);

  const loadProfile = useCallback(async () => {
    try {
      setProfile(await api.me());
      setStatus('signed-in');
    } catch {
      clearTokens();
      setProfile(null);
      setStatus('signed-out');
    }
  }, []);

  useEffect(() => {
    if (hasSession()) void loadProfile();
  }, [loadProfile]);

  useEffect(() => onAuthChange((signedIn) => {
    if (!signedIn) {
      setProfile(null);
      setStatus('signed-out');
      queryClient.clear();
      onSignOut?.();
    }
  }), [queryClient, onSignOut]);

  const value = useMemo<AuthValue>(() => ({
    status,
    profile,
    isAdmin: profile?.roles.includes('ADMIN') ?? false,
    login: async (email, password) => {
      setTokens(await api.login(email, password));
      await loadProfile();
    },
    register: async (email, password, displayName) => {
      setTokens(await api.register(email, password, displayName));
      await loadProfile();
    },
    logout: async () => {
      const refreshToken = localStorage.getItem('cadence.refreshToken');
      if (refreshToken) await api.logout(refreshToken).catch(() => undefined);
      clearTokens();
    },
  }), [status, profile, loadProfile]);

  return <AuthContext value={value}>{children}</AuthContext>;
}
