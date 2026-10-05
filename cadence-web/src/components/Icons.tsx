import type { SVGProps } from 'react';

type P = SVGProps<SVGSVGElement>;
const base = (props: P) => ({ viewBox: '0 0 24 24', fill: 'currentColor', 'aria-hidden': true, ...props });
const stroke = (props: P) => ({ viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2,
  strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const, 'aria-hidden': true, ...props });

export const PlayIcon = (p: P) => <svg {...base(p)}><path d="M7 4.8v14.4a1 1 0 0 0 1.5.86l11.6-7.2a1 1 0 0 0 0-1.72L8.5 3.94A1 1 0 0 0 7 4.8Z" /></svg>;
export const PauseIcon = (p: P) => <svg {...base(p)}><rect x="5.5" y="4" width="4.5" height="16" rx="1.2" /><rect x="14" y="4" width="4.5" height="16" rx="1.2" /></svg>;
export const NextIcon = (p: P) => <svg {...base(p)}><path d="M5 5.6v12.8a.9.9 0 0 0 1.4.75L15.5 13a1.2 1.2 0 0 0 0-2L6.4 4.85A.9.9 0 0 0 5 5.6Z" /><rect x="17" y="4.5" width="2.6" height="15" rx="1" /></svg>;
export const PrevIcon = (p: P) => <svg {...base(p)}><path d="M19 5.6v12.8a.9.9 0 0 1-1.4.75L8.5 13a1.2 1.2 0 0 1 0-2l9.1-6.15A.9.9 0 0 1 19 5.6Z" /><rect x="4.4" y="4.5" width="2.6" height="15" rx="1" /></svg>;
export const ShuffleIcon = (p: P) => <svg {...stroke(p)}><path d="M16 4h4v4M4 20l16-16M20 16v4h-4M15 15l5 5M4 4l5 5" /></svg>;
export const RepeatIcon = (p: P) => <svg {...stroke(p)}><path d="M17 2l3 3-3 3" /><path d="M4 11V9a4 4 0 0 1 4-4h12M7 22l-3-3 3-3" /><path d="M20 13v2a4 4 0 0 1-4 4H4" /></svg>;
export const RepeatOneIcon = (p: P) => <svg {...stroke(p)}><path d="M17 2l3 3-3 3" /><path d="M4 11V9a4 4 0 0 1 4-4h12M7 22l-3-3 3-3" /><path d="M20 13v2a4 4 0 0 1-4 4H4" /><path d="M11 10l1.5-1v6" strokeWidth={1.8} /></svg>;
export const HeartIcon = (p: P) => <svg {...stroke(p)}><path d="M12 20.3s-7.5-4.6-9.2-9.4C1.6 7.4 3.8 4 7.3 4c2 0 3.6 1.1 4.7 2.7C13.1 5.1 14.7 4 16.7 4c3.5 0 5.7 3.4 4.5 6.9-1.7 4.8-9.2 9.4-9.2 9.4Z" /></svg>;
export const HeartFilledIcon = (p: P) => <svg {...base(p)}><path d="M12 20.3s-7.5-4.6-9.2-9.4C1.6 7.4 3.8 4 7.3 4c2 0 3.6 1.1 4.7 2.7C13.1 5.1 14.7 4 16.7 4c3.5 0 5.7 3.4 4.5 6.9-1.7 4.8-9.2 9.4-9.2 9.4Z" /></svg>;
export const QueueIcon = (p: P) => <svg {...stroke(p)}><path d="M4 6h13M4 11h13M4 16h7" /><path d="M15 15.5v5l4-2.5-4-2.5Z" fill="currentColor" /></svg>;
export const VolumeIcon = (p: P) => <svg {...stroke(p)}><path d="M4 9.5h3l5-4v13l-5-4H4z" fill="currentColor" /><path d="M16 9a4.5 4.5 0 0 1 0 6M18.5 6.5a8 8 0 0 1 0 11" /></svg>;
export const VolumeLowIcon = (p: P) => <svg {...stroke(p)}><path d="M4 9.5h3l5-4v13l-5-4H4z" fill="currentColor" /><path d="M16 9a4.5 4.5 0 0 1 0 6" /></svg>;
export const MuteIcon = (p: P) => <svg {...stroke(p)}><path d="M4 9.5h3l5-4v13l-5-4H4z" fill="currentColor" /><path d="M16 9.5l5 5M21 9.5l-5 5" /></svg>;
export const SearchIcon = (p: P) => <svg {...stroke(p)}><circle cx="11" cy="11" r="7" /><path d="M20 20l-3.5-3.5" /></svg>;
export const HomeIcon = (p: P) => <svg {...stroke(p)}><path d="M4 10.5 12 4l8 6.5V20a1 1 0 0 1-1 1h-4.5v-6h-5v6H5a1 1 0 0 1-1-1z" /></svg>;
export const HomeFilledIcon = (p: P) => <svg {...base(p)}><path d="M12.6 3.3a1 1 0 0 0-1.2 0l-8 6.4a1 1 0 0 0-.4.8V20a1.5 1.5 0 0 0 1.5 1.5H9.5v-6.5h5v6.5h5A1.5 1.5 0 0 0 21 20v-9.5a1 1 0 0 0-.4-.8z" /></svg>;
export const LibraryIcon = (p: P) => <svg {...stroke(p)}><path d="M5 3.5v17M10 3.5v17M14.5 4.2l5.5 15.6" /></svg>;
export const PlusIcon = (p: P) => <svg {...stroke(p)}><path d="M12 5v14M5 12h14" /></svg>;
export const ClockIcon = (p: P) => <svg {...stroke(p)}><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 2" /></svg>;
export const MoreIcon = (p: P) => <svg {...base(p)}><circle cx="5" cy="12" r="1.8" /><circle cx="12" cy="12" r="1.8" /><circle cx="19" cy="12" r="1.8" /></svg>;
export const ChevronLeftIcon = (p: P) => <svg {...stroke(p)}><path d="M15 5l-7 7 7 7" /></svg>;
export const ChevronRightIcon = (p: P) => <svg {...stroke(p)}><path d="M9 5l7 7-7 7" /></svg>;
export const CloseIcon = (p: P) => <svg {...stroke(p)}><path d="M6 6l12 12M18 6 6 18" /></svg>;
export const CheckIcon = (p: P) => <svg {...stroke(p)}><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>;
export const VerifiedIcon = (p: P) => <svg {...base(p)}><path d="M12 2.5l2.4 1.8 3-.2.9 2.9 2.4 1.8-1 2.8 1 2.8-2.4 1.8-.9 2.9-3-.2L12 21.5l-2.4-1.8-3 .2-.9-2.9-2.4-1.8 1-2.8-1-2.8 2.4-1.8.9-2.9 3 .2z" /><path d="M8.3 12.2l2.5 2.5 5-5.2" fill="none" stroke="white" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" /></svg>;
export const UploadIcon = (p: P) => <svg {...stroke(p)}><path d="M12 16V4M7 9l5-5 5 5M5 20h14" /></svg>;
export const MusicIcon = (p: P) => <svg {...stroke(p)}><path d="M9 18V5l11-2v13" /><circle cx="6" cy="18" r="3" /><circle cx="17" cy="16" r="3" /></svg>;
export const ShieldIcon = (p: P) => <svg {...stroke(p)}><path d="M12 3l8 3v6c0 4.5-3.4 8-8 9-4.6-1-8-4.5-8-9V6z" /><path d="M9 12l2 2 4-4" /></svg>;
export const ChartIcon = (p: P) => <svg {...stroke(p)}><path d="M5 20V10M12 20V4M19 20v-7" /></svg>;
export const DragIcon = (p: P) => <svg {...base(p)}><circle cx="9" cy="6" r="1.6" /><circle cx="15" cy="6" r="1.6" /><circle cx="9" cy="12" r="1.6" /><circle cx="15" cy="12" r="1.6" /><circle cx="9" cy="18" r="1.6" /><circle cx="15" cy="18" r="1.6" /></svg>;
export const LogoutIcon = (p: P) => <svg {...stroke(p)}><path d="M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3M10 16l-4-4 4-4M6 12h10" /></svg>;
