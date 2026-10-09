import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react';
import { Toast } from '../ds';

interface ToastState { id: number; msg: string; error: boolean }
type Show = (msg: string, error?: boolean) => void;

const Ctx = createContext<Show>(() => {});

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<ToastState | null>(null);
  const timer = useRef<number | undefined>(undefined);
  const show = useCallback<Show>((msg, error = false) => {
    clearTimeout(timer.current);
    setToast({ id: Date.now(), msg, error });
    timer.current = window.setTimeout(() => setToast(null), error ? 5000 : 3200);
  }, []);
  return (
    <Ctx.Provider value={show}>
      {children}
      {toast && (
        <div style={{ position: 'fixed', left: '50%', bottom: 24, transform: 'translateX(-50%)', zIndex: 80 }}>
          <Toast key={toast.id} icon={toast.error ? 'circle-alert' : 'circle-check'} title={toast.msg} onClose={() => setToast(null)} />
        </div>
      )}
    </Ctx.Provider>
  );
}

export const useToast = () => useContext(Ctx);
