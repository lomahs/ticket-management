/** Date helpers. Dates are ISO `YYYY-MM-DD` strings, compared lexically. */

export const WDN = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

export const D = (s: string) => {
  const [y, m, d] = s.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d));
};
export const S = (dt: Date) => dt.toISOString().slice(0, 10);
export const add = (s: string, n: number) => {
  const d = D(s);
  d.setUTCDate(d.getUTCDate() + n);
  return S(d);
};
export const dow = (s: string) => D(s).getUTCDay();
export const dm = (s?: string | null) => (s ? s.slice(8, 10) + '/' + s.slice(5, 7) : '');
export const dayDiff = (a: string, b: string) => Math.round((D(b).getTime() - D(a).getTime()) / 864e5);
export const f1 = (n: number) => String(Math.round(n * 10) / 10);
/** Hours label: 7.5 → "7.5h". */
export const hrs = (n: number | null | undefined) => (n == null ? '–' : f1(n) + 'h');

export const monthDays = (mon: string) => {
  const [y, m] = mon.split('-').map(Number);
  const n = new Date(Date.UTC(y, m, 0)).getUTCDate();
  return [...Array(n)].map((_, i) => mon + '-' + String(i + 1).padStart(2, '0'));
};
export const monLabel = (mon: string) => MONTHS[Number(mon.slice(5)) - 1] + ' ' + mon.slice(0, 4);
export const shiftMon = (mon: string, d: number) => {
  let [y, m] = mon.split('-').map(Number);
  m += d;
  if (m > 12) { m = 1; y++; }
  if (m < 1) { m = 12; y--; }
  return y + '-' + String(m).padStart(2, '0');
};
export const mondayOf = (s: string) => add(s, -((dow(s) + 6) % 7));

export const longDate = (s: string) => {
  const names = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
  return `${names[dow(s)]}, ${dm(s)}/${s.slice(0, 4)}`;
};

export const relTime = (iso: string | null, now = Date.now()) => {
  if (!iso) return 'never';
  const m = Math.floor((now - new Date(iso).getTime()) / 60000);
  if (m < 1) return 'just now';
  if (m < 60) return m === 1 ? '1 minute ago' : `${m} minutes ago`;
  const h = Math.floor(m / 60);
  return h === 1 ? '1 hour ago' : `${h} hours ago`;
};
