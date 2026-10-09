import type { CSSProperties, ReactNode } from 'react';
import { Icon, type IconName } from './Icon';

export interface ToastProps {
  icon?: IconName | null;
  title?: string;
  children?: ReactNode;
  onClose?: () => void;
  style?: CSSProperties;
}

export function Toast({ icon = 'circle-check', title, children, onClose, style }: ToastProps) {
  return (
    <div
      role="status"
      style={{
        display: 'flex', alignItems: 'flex-start', gap: 12, width: 360, maxWidth: '100%', boxSizing: 'border-box',
        padding: 16, borderRadius: 'var(--radius-md)', background: 'var(--surface-inverse)', color: 'var(--text-inverse)',
        boxShadow: 'var(--shadow-overlay)', ...style,
      }}
    >
      {icon && <Icon name={icon} size={20} style={{ marginTop: 1 }} />}
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 4 }}>
        {title && <div style={{ font: 'var(--fw-semibold) var(--fs-small)/var(--lh-small) var(--font-text)' }}>{title}</div>}
        {children && <div style={{ font: 'var(--type-small)', opacity: 0.85 }}>{children}</div>}
      </div>
      {onClose && (
        <button
          type="button"
          aria-label="Dismiss"
          onClick={onClose}
          style={{ display: 'inline-flex', padding: 2, border: 0, background: 'transparent', color: 'inherit', cursor: 'pointer', opacity: 0.8 }}
        >
          <Icon name="x" size={16} />
        </button>
      )}
    </div>
  );
}
