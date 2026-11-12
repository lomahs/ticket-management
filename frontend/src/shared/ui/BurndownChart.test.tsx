import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { BurndownChart } from './BurndownChart';

afterEach(cleanup);

const day = (date: string, plan: number, act: number | null) => ({ date, planned_h: 4, logged_h: 0, plan_remaining_h: plan, actual_remaining_h: act });
const chart = (acts: (number | null)[]) => ({
  estimate_h: 12, start: '2026-10-05', end: '2026-10-08', plan_remaining_h: 4, actual_remaining_h: 6,
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
});
