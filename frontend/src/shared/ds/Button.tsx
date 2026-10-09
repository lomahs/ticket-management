import { useState, type ButtonHTMLAttributes, type CSSProperties, type ReactNode } from 'react';
import { Icon, type IconName } from './Icon';

const SIZES = {
  sm: { h: 32, px: 14, fs: 'var(--fs-small)', ic: 14 },
  md: { h: 40, px: 20, fs: 'var(--fs-body-sm)', ic: 16 },
  lg: { h: 48, px: 24, fs: 'var(--fs-body-sm)', ic: 18 },
};

const PAL = {
  primary: { bg: 'var(--button-primary-bg)', bgH: 'var(--button-primary-bg-hover)', fg: 'var(--button-primary-fg)', bd: 'transparent' },
  secondary: { bg: 'transparent', bgH: 'var(--surface-hover)', fg: 'var(--text-heading)', bd: 'var(--color-primary)' },
  subtle: { bg: 'transparent', bgH: 'var(--surface-hover)', fg: 'var(--text-heading)', bd: 'transparent' },
  inverse: { bg: 'var(--color-white)', bgH: 'var(--surface-tint)', fg: 'var(--color-primary)', bd: 'transparent' },
  link: { bg: 'transparent', bgH: 'transparent', fg: 'var(--text-link)', bd: 'transparent' },
};

export interface ButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'style'> {
  appearance?: keyof typeof PAL;
  size?: keyof typeof SIZES;
  shape?: 'pill' | 'square';
  iconBefore?: IconName;
  iconAfter?: IconName;
  fullWidth?: boolean;
  children?: ReactNode;
  style?: CSSProperties;
}

export function Button({
  appearance = 'primary', size = 'md', shape = 'pill', disabled = false, iconBefore, iconAfter,
  fullWidth = false, children, type = 'button', style, ...rest
}: ButtonProps) {
  const [h, setH] = useState(false);
  const [p, setP] = useState(false);
  const s = SIZES[size];
  const c = PAL[appearance];
  const isLink = appearance === 'link';
  return (
    <button
      type={type}
      disabled={disabled}
      onMouseEnter={() => setH(true)}
      onMouseLeave={() => { setH(false); setP(false); }}
      onMouseDown={() => setP(true)}
      onMouseUp={() => setP(false)}
      style={{
        display: fullWidth ? 'flex' : 'inline-flex',
        width: fullWidth ? '100%' : undefined,
        alignItems: 'center',
        justifyContent: 'center',
        gap: 8,
        height: isLink ? 'auto' : s.h,
        padding: isLink ? 0 : `0 ${s.px}px`,
        borderRadius: shape === 'pill' ? 'var(--radius-pill)' : 'var(--radius-sm)',
        border: '1px solid ' + (disabled && !isLink && appearance !== 'subtle' ? 'transparent' : c.bd),
        background: disabled
          ? appearance === 'primary' || appearance === 'inverse' ? 'var(--color-primary-a08)' : 'transparent'
          : h ? c.bgH : c.bg,
        color: disabled ? 'var(--text-disabled)' : isLink && h ? 'var(--color-primary)' : c.fg,
        font: `var(--fw-semibold) ${s.fs}/1 var(--font-text)`,
        textDecoration: isLink && h && !disabled ? 'underline' : 'none',
        textUnderlineOffset: 3,
        cursor: disabled ? 'not-allowed' : 'pointer',
        whiteSpace: 'nowrap',
        transform: p && !disabled && !isLink ? 'scale(0.98)' : 'none',
        transition: 'background var(--duration-fast) var(--easing-standard),color var(--duration-fast) var(--easing-standard),transform var(--duration-fast) var(--easing-standard)',
        ...style,
      }}
      {...rest}
    >
      {iconBefore && <Icon name={iconBefore} size={s.ic} />}
      {children}
      {iconAfter && <Icon name={iconAfter} size={s.ic} />}
    </button>
  );
}
