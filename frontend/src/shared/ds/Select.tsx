import { useId, useState, type ChangeEvent, type CSSProperties } from 'react';
import { Icon } from './Icon';
import { FieldLabel } from './TextField';

/** @param group options with a group are listed under it, after the ungrouped ones, in the order groups first appear */
export interface Option { value: string; label: string; group?: string }

export interface SelectProps {
  label?: string;
  options: Option[];
  value: string;
  onChange: (value: string, e: ChangeEvent<HTMLSelectElement>) => void;
  disabled?: boolean;
  id?: string;
  'aria-label'?: string;
  /** a tooltip, e.g. why the select is disabled */
  title?: string;
  style?: CSSProperties;
}

export function Select({ label, options, value, onChange, disabled = false, id, style, title, 'aria-label': ariaLabel }: SelectProps) {
  const [f, setF] = useState(false);
  const autoId = useId();
  const fid = id ?? autoId;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6, ...style }}>
      {label && <FieldLabel htmlFor={fid}>{label}</FieldLabel>}
      <div style={{ position: 'relative' }}>
        <select
          id={fid}
          aria-label={ariaLabel}
          title={title}
          value={value}
          onChange={(e) => onChange(e.target.value, e)}
          disabled={disabled}
          onFocus={() => setF(true)}
          onBlur={() => setF(false)}
          style={{
            appearance: 'none', WebkitAppearance: 'none', width: '100%', height: 40, boxSizing: 'border-box',
            padding: `0 36px 0 ${f ? 11 : 12}px`,
            borderRadius: 'var(--radius-md)',
            border: `${f ? '2px' : '1px'} solid ${f ? 'var(--border-focus)' : 'var(--border-strong)'}`,
            outline: 0,
            background: disabled ? 'var(--color-primary-a04)' : 'var(--surface-card)',
            font: 'var(--type-body-sm)',
            color: disabled ? 'var(--text-disabled)' : 'var(--text-body)',
            cursor: disabled ? 'not-allowed' : 'pointer',
          }}
        >
          {options.filter((o) => !o.group).map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          {[...new Set(options.flatMap((o) => (o.group ? [o.group] : [])))].map((g) => (
            <optgroup key={g} label={g}>
              {options.filter((o) => o.group === g).map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </optgroup>
          ))}
        </select>
        <Icon name="chevron-down" size={16} style={{ position: 'absolute', right: 12, top: 12, pointerEvents: 'none', color: 'var(--text-muted)' }} />
      </div>
    </div>
  );
}
