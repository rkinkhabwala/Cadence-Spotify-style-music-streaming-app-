import { lazy, Suspense, type ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createBrowserRouter, RouterProvider, type RouteObject } from 'react-router';
import { ApiError } from './api/client';
import { AuthProvider } from './auth/AuthProvider';
import { AppShell, Boot } from './components/AppShell';
import { ToastProvider } from './components/Toasts';
import { AlbumPage } from './pages/AlbumPage';
import { ArtistPage } from './pages/ArtistPage';
import { AuthPage } from './pages/AuthPage';
import { HomePage } from './pages/HomePage';
import { LikedPage } from './pages/LikedPage';
import { PlaylistPage } from './pages/PlaylistPage';
import { SearchPage } from './pages/SearchPage';
import { NotFoundPage } from './pages/states';
import { TopTracksPage } from './pages/TopTracksPage';
import { PlayerProvider, usePlayerActions } from './player/PlayerProvider';

// the admin tools are only for admins: keep them out of the main bundle
const AdminPage = lazy(() => import('./pages/AdminPage').then((m) => ({ default: m.AdminPage })));

export const routes: RouteObject[] = [
  { path: '/login', element: <AuthPage mode="login" /> },
  { path: '/register', element: <AuthPage mode="register" /> },
  {
    element: <AppShell />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'search', element: <SearchPage /> },
      { path: 'artist/:id', element: <ArtistPage /> },
      { path: 'album/:id', element: <AlbumPage /> },
      { path: 'playlist/:id', element: <PlaylistPage /> },
      { path: 'collection/tracks', element: <LikedPage /> },
      { path: 'me/top', element: <TopTracksPage /> },
      { path: 'admin', element: <Suspense fallback={<Boot />}><AdminPage /></Suspense> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];

export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 15_000,
        refetchOnWindowFocus: false,
        retry: (count, error) => !(error instanceof ApiError && error.status < 500) && count < 2,
      },
    },
  });
}

/** Stops playback when the session ends (logout or a revoked refresh token). */
function SessionBoundary({ children }: { children: ReactNode }) {
  const player = usePlayerActions();
  return <AuthProvider onSignOut={player.stop}>{children}</AuthProvider>;
}

/**
 * Provider order matters: the PlayerProvider (which owns the audio element) wraps the router, so no navigation, not
 * even between the login screen and the app, can unmount it.
 */
export function Providers({ children, queryClient }: { children: ReactNode; queryClient: QueryClient }) {
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <PlayerProvider>
          <SessionBoundary>{children}</SessionBoundary>
        </PlayerProvider>
      </ToastProvider>
    </QueryClientProvider>
  );
}

const queryClient = createQueryClient();
const router = createBrowserRouter(routes);

export function App() {
  return (
    <Providers queryClient={queryClient}>
      <RouterProvider router={router} />
    </Providers>
  );
}
