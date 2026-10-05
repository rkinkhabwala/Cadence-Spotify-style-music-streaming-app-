import { createContext, use, useCallback, useMemo, useState, type ReactNode } from 'react';

interface Toast {
  id: number;
  message: string;
  error: boolean;
}

const ToastContext = createContext<(message: string, error?: boolean) => void>(() => undefined);

export function useToast() {
  return use(ToastContext);
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const show = useCallback((message: string, error = false) => {
    const id = Date.now() + Math.random();
    setToasts((t) => [...t.slice(-2), { id, message, error }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 3200);
  }, []);
  const value = useMemo(() => show, [show]);
  return (
    <ToastContext value={value}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        {toasts.map((t) => <div key={t.id} className={`toast${t.error ? ' error' : ''}`}>{t.message}</div>)}
      </div>
    </ToastContext>
  );
}
