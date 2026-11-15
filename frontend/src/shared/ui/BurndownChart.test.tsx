import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { BurndownChart } from './BurndownChart';

afterEach(cleanup);

const day = (date: string, plan: number, act: number | null) => ({ date, off: false, planned_h: 4, logged_h: 0, plan_remaining_h: plan, actual_remaining_h: act, baseline_remaining_h: null as number | null });
const chart = (acts: (number | null)[]) => ({
  estimate_h: 12, start: '2026-10-05', end: '2026-10-08', plan_remaining_h: 4, actual_remaining_h: 6, baseline_h: null as number | null,
  days: ['2026-10-05', '2026-10-06', '2026-10-07', '2026-10-08'].map((d, k) => day(d, 12 - 4 * k, acts[k])),
});
const dots = (c: HTMLElement) => [...c.querySelectorAll<HTMLElement>('[data-actual]')].map((e) => e.dataset.actual);

describe('BurndownChart', () => {
  it('marks today with a dot when nothing was captured before', () => {
    const { container } = render(<BurndownChart b={chart([null, null, 6, null])} today="2026-10-07" empty="none" />);
    expect(dots(container)).toEqual(['2026-10-07']);
    expect(container.querySelectorAll('polyline')).toHaveLength(1); // the plan only: one point is no line
  });

  it('draws the actual days up to today as a line', () => {
    const { container } = render(<BurndownChart b={chart([11, 9, 6, null])} today="2026-10-07" empty="none" />);
    expect(dots(container)).toEqual([]);
    expect(container.querySelectorAll('polyline')).toHaveLength(2);
  });

  it('draws off days as narrower grey columns and labels the working days only', () => {
    const b = { ...chart([11, null, null, null]), days: [
      { ...day('2026-10-09', 12, 11), off: false }, { ...day('2026-10-10', 12, null), off: true },
      { ...day('2026-10-11', 12, null), off: true }, { ...day('2026-10-12', 8, null), off: false }] };
    const { container, getByText, queryByText } = render(<BurndownChart b={b} today="2026-10-09" empty="none" />);
    const col = (d: string) => container.querySelector<HTMLElement>(`[data-day="${d}"]`)!;
    expect(col('2026-10-10').dataset.off).toBe('true');
    expect(col('2026-10-10').style.flexGrow).toBe('0.4');
    expect(col('2026-10-10').style.background).toBe('var(--surface-hover)');
    expect(col('2026-10-12').style.flexGrow).toBe('1');
    expect(col('2026-10-12').dataset.off).toBeUndefined();
    expect(getByText('10/12')).toBeTruthy();
    expect(queryByText('10/10')).toBeNull();
  });

  it('draws the Baseline plan when there is one, and hides a line when asked', () => {
    const base = chart([11, 9, 6, null]);
    const b = { ...base, baseline_h: 20, days: base.days.map((d, k) => ({ ...d, baseline_remaining_h: 20 - 5 * k })) };
    const { container, rerender } = render(<BurndownChart b={b} today="2026-10-07" empty="none" />);
    const line = (name: string) => container.querySelector(`polyline[data-line="${name}"]`);
    expect(line('baseline')).toBeTruthy();
    expect(line('plan')).toBeTruthy();
    expect(line('actual')).toBeTruthy();
    expect(container.textContent).toContain('20.00h'); // the y axis covers the Baseline plan, above the 12h estimate
    rerender(<BurndownChart b={b} today="2026-10-07" empty="none" show={{ baseline: false, plan: true, actual: true }} />);
    expect(line('baseline')).toBeNull();
    rerender(<BurndownChart b={b} today="2026-10-07" empty="none" show={{ baseline: true, plan: false, actual: false }} />);
    expect(line('plan')).toBeNull();
    expect(line('actual')).toBeNull();
    expect(line('baseline')).toBeTruthy();
  });

  it('draws no Baseline plan without one', () => {
    const { container } = render(<BurndownChart b={chart([11, 9, 6, null])} today="2026-10-07" empty="none" />);
    expect(container.querySelector('polyline[data-line="baseline"]')).toBeNull();
  });
});
