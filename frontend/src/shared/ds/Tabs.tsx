import { useState, type CSSProperties } from 'react';

export interface Tab<T extends string> { value: T; label: string }

export interface TabsProps<T extends string> {
  tabs: Tab<T>[];
  value: T;
  onChange: (value: T) => void;
  size?: 'md' | 'lg';
  style?: CSSProperties;
}

export function Tabs<T extends string>({ tabs, value, onChange, size = 'md', style }: TabsProps<T>) {
  const [hov, setHov] = useState<T | null>(null);
  return (
    <div role="tablist" style={{ display: 'flex', gap: 24, borderBottom: '1px solid var(--border-subtle)', overflowX: 'auto', ...style }}>
      {tabs.map((t) => {
        const on = t.value === value;
        return (
          <button
            key={t.value}
            role="tab"
            aria-selected={on}
            onClick={() => onChange(t.value)}
            onMouseEnter={() => setHov(t.value)}
            onMouseLeave={() => setHov(null)}
            style={{
              position: 'relative', background: 'transparent', border: 0,
              borderBottom: '2px solid ' + (on ? 'var(--color-accent)' : hov === t.value ? 'var(--border-strong)' : 'transparent'),
              padding: size === 'lg' ? '14px 0' : '10px 0', marginBottom: -1, cursor: 'pointer', whiteSpace: 'nowrap',
              font: `var(--fw-semibold) ${size === 'lg' ? 'var(--fs-body-sm)' : 'var(--fs-small)'}/1.2 var(--font-text)`,
              color: on || hov === t.value ? 'var(--text-heading)' : 'var(--text-muted)',
              transition: 'color var(--duration-fast) var(--easing-standard),border-color var(--duration-fast) var(--easing-standard)',
            }}
          >
            {t.label}
          </button>
        );
      })}
    </div>
  );
}
