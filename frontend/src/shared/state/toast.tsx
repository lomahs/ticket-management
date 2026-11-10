import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react';
import { Toast } from '../ds';

/** `true` is an error; `'success'` reports a write that finished in the background, on green. */
type Tone = boolean | 'success';
interface ToastState { id: number; msg: string; tone: Tone }
type Show = (msg: string, tone?: Tone) => void;

const Ctx = createContext<Show>(() => {});

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<ToastState | null>(null);
  const timer = useRef<number | undefined>(undefined);
  const show = useCallback<Show>((msg, tone = false) => {
    clearTimeout(timer.current);
    setToast({ id: Date.now(), msg, tone });
    timer.current = window.setTimeout(() => setToast(null), tone === false ? 3200 : 5000);
  }, []);
  return (
    <Ctx.Provider value={show}>
      {children}
      {toast && (
        <div style={{ position: 'fixed', left: '50%', bottom: 24, transform: 'translateX(-50%)', zIndex: 80 }}>
          <Toast key={toast.id} icon={toast.tone === true ? 'circle-alert' : 'circle-check'} title={toast.msg} onClose={() => setToast(null)}
            style={toast.tone === 'success' ? { background: 'var(--toast-ok-bg)', color: 'var(--toast-ok-fg)' } : undefined} />
        </div>
      )}
    </Ctx.Provider>
  );
}

export const useToast = () => useContext(Ctx);
