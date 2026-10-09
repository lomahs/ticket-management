import { useId, useState, type ChangeEvent, type CSSProperties, type InputHTMLAttributes, type ReactNode } from 'react';
import { Icon, type IconName } from './Icon';

export function FieldLabel({ htmlFor, children }: { htmlFor: string; children: ReactNode }) {
  return (
    <label htmlFor={htmlFor} style={{ font: 'var(--fw-semibold) var(--fs-small)/var(--lh-small) var(--font-text)', color: 'var(--text-heading)' }}>
      {children}
    </label>
  );
}

export interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'onChange' | 'style' | 'size' | 'value'> {
  label?: string;
  iconBefore?: IconName;
  value: string;
  onChange: (value: string, e: ChangeEvent<HTMLInputElement>) => void;
  /** `sm` is the 30px pill used for inline filters. */
  size?: 'md' | 'sm';
  mono?: boolean;
  style?: CSSProperties;
}

export function TextField({
  label, iconBefore, id, value, onChange, type = 'text', disabled = false, size = 'md', mono = false, style, ...rest
}: TextFieldProps) {
  const [f, setF] = useState(false);
  const autoId = useId();
  const fid = id ?? autoId;
  const sm = size === 'sm';
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6, ...style }}>
      {label && <FieldLabel htmlFor={fid}>{label}</FieldLabel>}
      <div
        style={{
          display: 'flex', alignItems: 'center', gap: sm ? 6 : 8,
          height: sm ? 30 : 40, boxSizing: 'border-box',
          borderRadius: sm ? 999 : 'var(--radius-md)',
          background: disabled ? 'var(--color-primary-a04)' : 'var(--surface-card)',
          border: `${f ? '2px' : '1px'} solid ${f ? 'var(--border-focus)' : 'var(--border-strong)'}`,
          paddingLeft: (sm ? 10 : 12) - (f ? 1 : 0), paddingRight: 12,
          color: 'var(--text-muted)',
          transition: 'border-color var(--duration-fast) var(--easing-standard)',
        }}
      >
        {iconBefore && <Icon name={iconBefore} size={16} />}
        <input
          id={fid}
          type={type}
          value={value}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value, e)}
          onFocus={() => setF(true)}
          onBlur={() => setF(false)}
          style={{
            flex: 1, minWidth: 0, border: 0, outline: 0, background: 'transparent',
            font: 'var(--type-body-sm)', fontSize: sm ? 12 : undefined,
            fontFamily: mono ? 'var(--font-mono)' : undefined,
            color: disabled ? 'var(--text-disabled)' : 'var(--text-body)',
          }}
          {...rest}
        />
      </div>
    </div>
  );
}
