import { useEffect, useRef, useState } from 'react';
import { Button, Icon, type IconName } from '../../../shared/ds';

export interface MenuItem { label: string; icon?: IconName; title?: string; disabled?: boolean; onClick: () => void }

/** A button that opens a short list of the less frequent actions; it closes on a pick, Escape or a click outside. */
export function MoreMenu({ label = 'More', items }: { label?: string; items: (MenuItem | 'sep')[] }) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    if (!open) return;
    box.current?.querySelector<HTMLButtonElement>('[role="menuitem"]:not(:disabled)')?.focus();
    const out = (e: MouseEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false); };
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', out);
    document.addEventListener('keydown', key);
    return () => { document.removeEventListener('mousedown', out); document.removeEventListener('keydown', key); };
  }, [open]);
  return (
    <span ref={box} style={{ position: 'relative', display: 'inline-flex' }}>
      <Button appearance="secondary" size="sm" iconAfter="chevron-down" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((x) => !x)}>
        {label}
      </Button>
      {open && (
        <div className="menu" role="menu" aria-label={label} style={{ right: 0, top: 'calc(100% + 6px)' }}>
          {items.map((it, i) => (it === 'sep' ? <hr key={i} /> : (
            <button key={it.label} type="button" role="menuitem" title={it.title} disabled={it.disabled}
              onClick={() => { setOpen(false); it.onClick(); }}>
              {it.icon && <Icon name={it.icon} size={15} />}{it.label}
            </button>
          )))}
        </div>
      )}
    </span>
  );
}
