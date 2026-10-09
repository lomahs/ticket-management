import { useState, type ButtonHTMLAttributes, type CSSProperties } from 'react';
import { Icon, type IconName } from './Icon';

const SZ = { sm: 32, md: 40, lg: 48 };
const PAL = {
  primary: ['var(--button-primary-bg)', 'var(--button-primary-bg-hover)', 'var(--text-inverse)', 'transparent'],
  secondary: ['transparent', 'var(--surface-hover)', 'var(--text-heading)', 'var(--color-primary)'],
  subtle: ['transparent', 'var(--surface-pressed)', 'var(--text-heading)', 'transparent'],
  inverse: ['transparent', 'rgba(255,255,255,.12)', 'var(--text-inverse)', 'transparent'],
};

export interface IconButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'style'> {
  icon: IconName;
  label: string;
  appearance?: keyof typeof PAL;
  size?: keyof typeof SZ;
  shape?: 'circle' | 'square';
  style?: CSSProperties;
}

export function IconButton({
  icon, label, appearance = 'subtle', size = 'md', shape = 'circle', disabled = false, style, type = 'button', ...rest
}: IconButtonProps) {
  const [h, setH] = useState(false);
  const d = SZ[size];
  const pal = PAL[appearance];
  return (
    <button
      type={type}
      aria-label={label}
      title={label}
      disabled={disabled}
      onMouseEnter={() => setH(true)}
      onMouseLeave={() => setH(false)}
      style={{
        display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
        width: d, height: d, padding: 0, flex: 'none',
        borderRadius: shape === 'circle' ? 'var(--radius-pill)' : 'var(--radius-sm)',
        border: '1px solid ' + pal[3],
        background: h && !disabled ? pal[1] : pal[0],
        color: disabled ? 'var(--text-disabled)' : pal[2],
        cursor: disabled ? 'not-allowed' : 'pointer',
        transition: 'background var(--duration-fast) var(--easing-standard)',
        ...style,
      }}
      {...rest}
    >
      <Icon name={icon} size={d >= 48 ? 20 : 16} />
    </button>
  );
}
