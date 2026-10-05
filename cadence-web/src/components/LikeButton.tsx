import { useState } from 'react';
import { useLikeToggle, useLikedIds } from '../api/hooks';
import { HeartFilledIcon, HeartIcon } from './Icons';
import { useToast } from './Toasts';

export function LikeButton({ trackId, className = 'icon-btn' }: { trackId: string; className?: string }) {
  const liked = useLikedIds().has(trackId);
  const toggle = useLikeToggle();
  const toast = useToast();
  const [optimistic, setOptimistic] = useState<boolean | null>(null);
  const on = optimistic ?? liked;
  return (
    <button
      type="button"
      className={`${className}${on ? ' on' : ''}`}
      aria-label={on ? 'Remove from Liked Songs' : 'Save to Liked Songs'}
      aria-pressed={on}
      onClick={(e) => {
        e.stopPropagation();
        setOptimistic(!on);
        toggle.mutate({ id: trackId, on: !on }, {
          onSuccess: () => toast(!on ? 'Added to Liked Songs' : 'Removed from Liked Songs'),
          onError: () => toast('Could not update Liked Songs', true),
          onSettled: () => setOptimistic(null),
        });
      }}
    >
      {on ? <HeartFilledIcon style={{ color: 'var(--accent)' }} /> : <HeartIcon />}
    </button>
  );
}
