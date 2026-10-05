import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './endpoints';
import type { Page } from './types';

/** Loads every page of a cursor-paginated collection (library collections are small). */
async function all<T>(load: (cursor?: string) => Promise<Page<T>>, max = 2000): Promise<T[]> {
  const items: T[] = [];
  let cursor: string | undefined;
  do {
    const page = await load(cursor);
    items.push(...page.items);
    cursor = page.nextCursor ?? undefined;
  } while (cursor && items.length < max);
  return items;
}

export const keys = {
  likes: ['library', 'likes'] as const,
  follows: ['library', 'follows'] as const,
  savedAlbums: ['library', 'saved-albums'] as const,
  playlists: ['library', 'playlists'] as const,
  playlist: (id: string) => ['playlist', id] as const,
  home: ['home'] as const,
  recent: ['recently-played'] as const,
};

export function useLikedTracks() {
  return useQuery({ queryKey: keys.likes, queryFn: () => all((c) => api.likedTracks(c)), staleTime: 60_000 });
}

export function useFollowedArtists() {
  return useQuery({ queryKey: keys.follows, queryFn: () => all((c) => api.followedArtists(c)), staleTime: 60_000 });
}

export function useSavedAlbums() {
  return useQuery({ queryKey: keys.savedAlbums, queryFn: () => all((c) => api.savedAlbums(c)), staleTime: 60_000 });
}

export function useMyPlaylists() {
  return useQuery({ queryKey: keys.playlists, queryFn: () => all(() => api.myPlaylists()), staleTime: 30_000 });
}

/** A toggle over a cached id set with an optimistic update (rolled back on error). */
function useToggle(key: readonly unknown[], idOf: (item: never) => string, add: (id: string) => Promise<void>,
                   remove: (id: string) => Promise<void>) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, on }: { id: string; on: boolean }) => (on ? add(id) : remove(id)),
    onMutate: async ({ id, on }) => {
      await client.cancelQueries({ queryKey: key });
      const previous = client.getQueryData<unknown[]>(key);
      if (previous && !on) client.setQueryData(key, previous.filter((item) => idOf(item as never) !== id));
      return { previous };
    },
    onError: (_e, _v, context) => {
      if (context?.previous) client.setQueryData(key, context.previous);
    },
    onSettled: () => client.invalidateQueries({ queryKey: key }),
  });
}

export function useLikeToggle() {
  return useToggle(keys.likes, (i: { track: { id: string } }) => i.track.id, api.like, api.unlike);
}

export function useFollowToggle() {
  return useToggle(keys.follows, (i: { artist: { id: string } }) => i.artist.id, api.follow, api.unfollow);
}

export function useSaveAlbumToggle() {
  return useToggle(keys.savedAlbums, (i: { album: { id: string } }) => i.album.id, api.saveAlbum, api.unsaveAlbum);
}

export function useLikedIds(): Set<string> {
  const { data } = useLikedTracks();
  return new Set((data ?? []).map((l) => l.track.id));
}
