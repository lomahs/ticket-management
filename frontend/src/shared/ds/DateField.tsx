import { useEffect, useId, useLayoutEffect, useRef, useState, type CSSProperties, type KeyboardEvent, type ReactNode, type RefObject } from 'react';
import { createPortal } from 'react-dom';
import { DayPicker, type Matcher } from 'react-day-picker';
import 'react-day-picker/style.css';
import { parseYmd, ymd } from '../format/dates';
import { Icon } from './Icon';
import { FieldLabel } from './TextField';

/** ISO yyyy-MM-dd ↔ a local Date for the calendar. */
const toDate = (iso: string) => {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d);
};
const toIso = (dt: Date) => `${dt.getFullYear()}-${String(dt.getMonth() + 1).padStart(2, '0')}-${String(dt.getDate()).padStart(2, '0')}`;
const inRange = (iso: string, min?: string, max?: string) => (!min || iso >= min) && (!max || iso <= max);
const limits = (min?: string, max?: string): Matcher[] =>
  [min && { before: toDate(min) }, max && { after: toDate(max) }].filter(Boolean) as Matcher[];

/**
 * The text of a date input shown as yyyy/MM/dd. A complete date is sent as it is typed (with `sendOnLeave`, only on blur
 * or Enter); on blur or Enter a looser one ("2026/1/5") is taken too, and anything else goes back to the last date with the
 * field marked. A date is sent once, even when the value comes back later (a save to the server).
 */
function useDateText(value: string, commit: (iso: string) => void, min?: string, max?: string, sendOnLeave = false) {
  const [text, setText] = useState(ymd(value));
  const [shown, setShown] = useState(value);
  const [bad, setBad] = useState(false);
  const [sent, setSent] = useState<string | null>(null);
  if (value !== shown) {
    setShown(value);
    setText(ymd(value));
    setBad(false);
    setSent(null);
  }
  const send = (iso: string) => {
    if (iso === value || iso === sent) return;
    setSent(iso);
    commit(iso);
  };
  const type = (t: string) => {
    setText(t);
    setBad(false);
    if (sendOnLeave) return;
    if (!t.trim()) { send(''); return; }
    const iso = parseYmd(t);
    if (iso && inRange(iso, min, max)) send(iso);
  };
  const settle = () => {
    if (!text.trim()) {
      send('');
      setText(ymd(value));
      return;
    }
    const iso = parseYmd(text, true);
    if (iso && inRange(iso, min, max)) {
      send(iso);
      setText(ymd(iso));
    } else {
      setText(ymd(sent ?? value));
      setBad(true);
    }
  };
  const pick = (iso: string) => {
    setText(ymd(iso));
    setBad(false);
    send(iso);
  };
  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>, open: () => void) => {
    if (e.key === 'Enter') settle();
    if (e.key === 'ArrowDown' && e.altKey) { e.preventDefault(); open(); }
  };
  return { text, bad, type, settle, pick, onKeyDown };
}

/** The calendar under (or above) `anchor`, over everything; closes on Escape, a click outside or a scroll outside. */
function Popover({ anchor, onClose, children }: { anchor: RefObject<HTMLElement | null>; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  const [at, setAt] = useState<{ top: number; left: number } | null>(null);
  useLayoutEffect(() => {
    const a = anchor.current?.getBoundingClientRect();
    const p = ref.current?.getBoundingClientRect();
    if (!a || !p) return;
    const below = a.bottom + 4 + p.height <= window.innerHeight || a.top - 4 - p.height < 0;
    setAt({
      top: below ? a.bottom + 4 : a.top - 4 - p.height,
      left: Math.max(8, Math.min(a.left, window.innerWidth - p.width - 8)),
    });
  }, [anchor]);
  useEffect(() => {
    const outside = (e: Event) => {
      const t = e.target as Node;
      if (!ref.current?.contains(t) && !anchor.current?.contains(t)) onClose();
    };
    const esc = (e: globalThis.KeyboardEvent) => { if (e.key === 'Escape') { e.stopPropagation(); onClose(); } };
    document.addEventListener('mousedown', outside);
    document.addEventListener('scroll', outside, true);
    document.addEventListener('keydown', esc, true);
    return () => {
      document.removeEventListener('mousedown', outside);
      document.removeEventListener('scroll', outside, true);
      document.removeEventListener('keydown', esc, true);
    };
  }, [anchor, onClose]);
  return createPortal(
    <div ref={ref} className="dp-pop" role="dialog" aria-label="Choose date" style={at ?? { top: 0, left: 0, visibility: 'hidden' }}>
      {children}
    </div>,
    document.body,
  );
}

function CalendarButton({ label, disabled, onClick }: { label: string; disabled?: boolean; onClick: () => void }) {
  return (
    <button type="button" className="dp-btn" aria-label={label} title={label} disabled={disabled} onClick={onClick}>
      <Icon name="calendar" size={14} />
    </button>
  );
}

const inputStyle = (disabled: boolean, sm: boolean): CSSProperties => ({
  flex: 1, minWidth: 0, border: 0, outline: 0, background: 'transparent', padding: 0,
  font: 'var(--type-body-sm)', fontSize: sm ? 12 : undefined, fontVariantNumeric: 'tabular-nums',
  color: disabled ? 'var(--text-disabled)' : 'var(--text-body)',
});

const frameStyle = (focus: boolean, disabled: boolean, invalid: boolean, sm: boolean): CSSProperties => ({
  display: 'flex', alignItems: 'center', gap: 6,
  height: sm ? 30 : 40, boxSizing: 'border-box',
  borderRadius: sm ? 999 : 'var(--radius-md)',
  background: disabled ? 'var(--color-primary-a04)' : 'var(--surface-card)',
  border: `${focus ? '2px' : '1px'} solid ${invalid ? 'var(--hl-red-fg)' : focus ? 'var(--border-focus)' : 'var(--border-strong)'}`,
  paddingLeft: (sm ? 10 : 12) - (focus ? 1 : 0), paddingRight: 4 - (focus ? 1 : 0),
  transition: 'border-color var(--duration-fast) var(--easing-standard)',
});

export interface DateFieldProps {
  label?: string;
  /** ISO yyyy-MM-dd, or '' for none. */
  value: string;
  /** Called with the ISO date, or '' when the text is cleared. */
  onChange: (iso: string) => void;
  disabled?: boolean;
  min?: string;
  max?: string;
  size?: 'md' | 'sm';
  /** A bare input for table cells: `style` is the input's own style, with no frame and no label. */
  bare?: boolean;
  /** Send a typed date only when the field is left or Enter is pressed; a date picked on the calendar is sent at once. */
  sendOnLeave?: boolean;
  style?: CSSProperties;
  id?: string;
  title?: string;
  'aria-label'?: string;
  'aria-invalid'?: boolean;
}

/** A date typed as yyyy/MM/dd or picked on a calendar (Monday first). */
export function DateField({
  label, value, onChange, disabled = false, min, max, size = 'md', bare = false, sendOnLeave = false, style, id, title, ...aria
}: DateFieldProps) {
  const autoId = useId();
  const fid = id ?? autoId;
  const anchor = useRef<HTMLSpanElement>(null);
  const [open, setOpen] = useState(false);
  const [focus, setFocus] = useState(false);
  const t = useDateText(value, onChange, min, max, sendOnLeave);
  const invalid = t.bad || !!aria['aria-invalid'];
  const name = aria['aria-label'] ?? label ?? 'date';
  const input = (s: CSSProperties) => (
    <input
      id={fid} type="text" inputMode="numeric" autoComplete="off" placeholder="yyyy/mm/dd" value={t.text} disabled={disabled} title={title}
      aria-label={aria['aria-label']} aria-invalid={invalid || undefined}
      onChange={(e) => t.type(e.target.value)} onFocus={() => setFocus(true)} onBlur={() => { setFocus(false); t.settle(); }}
      onKeyDown={(e) => t.onKeyDown(e, () => setOpen(true))} style={s}
    />
  );
  const calendar = open && (
    <Popover anchor={anchor} onClose={() => setOpen(false)}>
      <DayPicker
        mode="single" weekStartsOn={1} showOutsideDays autoFocus
        selected={value ? toDate(value) : undefined} defaultMonth={value ? toDate(value) : min ? toDate(min) : undefined}
        disabled={limits(min, max)}
        onSelect={(_, d) => { t.pick(toIso(d)); setOpen(false); }}
      />
    </Popover>
  );
  const button = <CalendarButton label={`Open calendar, ${name}`} disabled={disabled} onClick={() => setOpen((o) => !o)} />;

  if (bare) {
    return (
      <span ref={anchor} className="dp-bare">
        {input({ ...style, paddingRight: 26, ...(invalid ? { borderColor: 'var(--hl-red-fg)' } : {}) })}
        {button}
        {calendar}
      </span>
    );
  }
  const sm = size === 'sm';
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6, ...style }}>
      {label && <FieldLabel htmlFor={fid}>{label}</FieldLabel>}
      <span ref={anchor} style={frameStyle(focus, disabled, invalid, sm)}>
        {input(inputStyle(disabled, sm))}
        {button}
      </span>
      {calendar}
    </div>
  );
}

export interface DateRangeFieldProps {
  label?: string;
  /** ISO yyyy-MM-dd, or '' for none. */
  from: string;
  to: string;
  onChange: (range: { from: string; to: string }) => void;
  disabled?: boolean;
  invalid?: boolean;
  size?: 'md' | 'sm';
  fromLabel?: string;
  toLabel?: string;
  style?: CSSProperties;
}

/**
 * A From – To range in one field: each end is typed as yyyy/MM/dd, or both are picked on a two-month calendar
 * (the first click starts the range, the second ends it).
 */
export function DateRangeField({
  label, from, to, onChange, disabled = false, invalid = false, size = 'md', fromLabel = 'From', toLabel = 'To', style,
}: DateRangeFieldProps) {
  const autoId = useId();
  const anchor = useRef<HTMLSpanElement>(null);
  const [open, setOpen] = useState(false);
  const [focus, setFocus] = useState(false);
  // The first click of a range on the calendar, until the second one ends it.
  const [start, setStart] = useState<string | null>(null);
  const a = useDateText(from, (v) => onChange({ from: v, to }));
  const b = useDateText(to, (v) => onChange({ from, to: v }));
  const sm = size === 'sm';
  const close = () => { setOpen(false); setStart(null); };
  const pick = (d: Date) => {
    const iso = toIso(d);
    if (start == null) { setStart(iso); return; }
    onChange(iso < start ? { from: iso, to: start } : { from: start, to: iso });
    close();
  };
  const selected = start != null ? { from: toDate(start), to: undefined } : from ? { from: toDate(from), to: to ? toDate(to) : undefined } : undefined;
  const input = (t: ReturnType<typeof useDateText>, aria: string, id?: string) => (
    <input
      id={id} type="text" inputMode="numeric" autoComplete="off" placeholder="yyyy/mm/dd" value={t.text} disabled={disabled} aria-label={aria}
      aria-invalid={t.bad || invalid || undefined}
      onChange={(e) => t.type(e.target.value)} onFocus={() => setFocus(true)} onBlur={() => { setFocus(false); t.settle(); }}
      onKeyDown={(e) => t.onKeyDown(e, () => setOpen(true))} style={{ ...inputStyle(disabled, sm), flex: 'none', width: sm ? 78 : 88 }}
    />
  );
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6, ...style }}>
      {label && <FieldLabel htmlFor={autoId}>{label}</FieldLabel>}
      <span ref={anchor} style={{ ...frameStyle(focus, disabled, invalid || a.bad || b.bad, sm), display: 'inline-flex' }}>
        {input(a, fromLabel, autoId)}
        <span className="muted">–</span>
        {input(b, toLabel)}
        <span className="spacer" />
        <CalendarButton label={`Open calendar, ${label ?? `${fromLabel} – ${toLabel}`}`} disabled={disabled} onClick={() => (open ? close() : setOpen(true))} />
      </span>
      {open && (
        <Popover anchor={anchor} onClose={close}>
          <DayPicker
            mode="range" weekStartsOn={1} showOutsideDays autoFocus numberOfMonths={2}
            selected={selected} defaultMonth={from ? toDate(from) : undefined}
            onSelect={(_, d) => pick(d)}
          />
        </Popover>
      )}
    </div>
  );
}
