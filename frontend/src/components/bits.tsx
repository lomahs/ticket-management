import type { CSSProperties, ReactNode } from 'react';
import type { Av } from '../lib/domain';

/** Member initial in a coloured circle. */
export function Avatar({ a, size = 20, fs, title, style }: { a: Pick<Av, 'ini' | 'color'> & { name?: string }; size?: number; fs?: number; title?: string; style?: CSSProperties }) {
  return (
    <span
      title={title}
      style={{
        width: size, height: size, borderRadius: '50%', background: a.color, color: '#fff', flex: 'none',
        font: `600 ${fs ?? (size >= 28 ? 12 : size >= 20 ? 10 : 9)}px/${size}px var(--font-text)`, textAlign: 'center', ...style,
      }}
    >
      {a.ini}
    </span>
  );
}

export function Dot({ c, size = 8, style }: { c: string; size?: number; style?: CSSProperties }) {
  return <span className="dot" style={{ width: size, height: size, background: c, ...style }} />;
}

export function StatusPill({ bg, dot, children }: { bg: string; dot: string; children: ReactNode }) {
  return (
    <span className="status-pill" style={{ background: bg }}>
      <Dot c={dot} size={7} />
      {children}
    </span>
  );
}

/** Pill segmented control. */
export function Seg<T extends string>({ items, value, onPick, outline }: { items: [T, string][]; value: T; onPick: (v: T) => void; outline?: boolean }) {
  return (
    <div className={outline ? 'seg-outline' : 'seg'}>
      {items.map(([k, l]) => (
        <button
          key={k}
          onClick={() => onPick(k)}
          style={{ background: value === k ? 'var(--button-primary-bg)' : 'transparent', color: value === k ? 'var(--button-primary-fg)' : 'var(--text-heading)' }}
        >
          {l}
        </button>
      ))}
    </div>
  );
}

/** Est / Spent / Remain mini grid used across My Work lists. */
export function EffortTrio({ est, spent, rem, spentFg }: { est: string; spent: string; rem: string; spentFg: string }) {
  const v = { font: '500 12px/1.2 var(--font-mono)', color: 'var(--text-heading)' };
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: '2px 8px' }}>
      <span className="mini-label">Est</span><span className="mini-label">Spent</span><span className="mini-label">Remain</span>
      <span style={v}>{est}</span>
      <span style={{ ...v, fontWeight: 600, color: spentFg }}>{spent}</span>
      <span style={v}>{rem}</span>
    </div>
  );
}

export function PageHead({ eyebrow, title, children }: { eyebrow: ReactNode; title: string; children?: ReactNode }) {
  return (
    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 12, flexWrap: 'wrap' }}>
      <div style={{ flex: 1, minWidth: 240 }}>
        <div className="eyebrow">{eyebrow}</div>
        <h1 className="h1" style={{ marginTop: 2 }}>{title}</h1>
      </div>
      {children}
    </div>
  );
}

export function Legend({ items }: { items: { label: string; bg: string; ab?: string }[] }) {
  return (
    <div className="legend">
      {items.map((lg) => (
        <span key={lg.label}>
          <span className="swatch" style={{ border: '1px solid var(--border-subtle)', background: lg.bg }} />
          {lg.label}
        </span>
      ))}
    </div>
  );
}
