import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DateField, DateRangeField } from './DateField';

afterEach(cleanup);

const input = (name: string) => screen.getByLabelText(name) as HTMLInputElement;

describe('DateField', () => {
  it('shows yyyy/MM/dd and sends a typed date once it is complete', () => {
    const onChange = vi.fn();
    render(<DateField aria-label="Due" value="2026-10-07" onChange={onChange} />);
    expect(input('Due').value).toBe('2026/10/07');
    fireEvent.change(input('Due'), { target: { value: '2026/10/1' } });
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.change(input('Due'), { target: { value: '2026/10/15' } });
    expect(onChange).toHaveBeenCalledWith('2026-10-15');
    fireEvent.blur(input('Due'));
    expect(onChange).toHaveBeenCalledTimes(1); // not again on blur
  });

  it('takes a loose date on blur and puts back one it cannot read', () => {
    const onChange = vi.fn();
    render(<DateField aria-label="Due" value="2026-10-07" onChange={onChange} min="2026-01-01" />);
    fireEvent.change(input('Due'), { target: { value: '2026/1/5' } });
    fireEvent.blur(input('Due'));
    expect(onChange).toHaveBeenCalledWith('2026-01-05');
    expect(input('Due').value).toBe('2026/01/05');
    fireEvent.change(input('Due'), { target: { value: '2025/12/31' } }); // before min
    fireEvent.blur(input('Due'));
    expect(input('Due').value).toBe('2026/01/05');
    expect(input('Due').getAttribute('aria-invalid')).toBe('true');
    expect(onChange).toHaveBeenCalledTimes(1);
  });

  it('with sendOnLeave, sends a typed date only when the field is left', () => {
    const onChange = vi.fn();
    render(<DateField aria-label="Due" value="2026-10-07" onChange={onChange} sendOnLeave />);
    fireEvent.change(input('Due'), { target: { value: '2026/10/15' } });
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.keyDown(input('Due'), { key: 'Enter' });
    fireEvent.blur(input('Due'));
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith('2026-10-15');
  });

  it('picks a day on a Monday-first calendar', () => {
    function Field() {
      const [v, setV] = useState('2026-10-07');
      return <DateField aria-label="Due" value={v} onChange={setV} />;
    }
    render(<Field />);
    fireEvent.click(screen.getByRole('button', { name: 'Open calendar, Due' }));
    const cal = screen.getByRole('dialog', { name: 'Choose date' });
    expect(cal.querySelector('.rdp-weekday')?.getAttribute('aria-label')).toBe('Monday');
    fireEvent.click(within(cal).getByRole('button', { name: /October 20th, 2026/ }));
    expect(input('Due').value).toBe('2026/10/20');
    expect(screen.queryByRole('dialog', { name: 'Choose date' })).toBeNull();
  });
});

describe('DateRangeField', () => {
  function Range({ onChange }: { onChange?: (r: { from: string; to: string }) => void }) {
    const [r, setR] = useState({ from: '2026-10-05', to: '2026-10-09' });
    return <DateRangeField label="Dates" from={r.from} to={r.to} onChange={(x) => { setR(x); onChange?.(x); }} />;
  }

  it('types each end', () => {
    const onChange = vi.fn();
    render(<Range onChange={onChange} />);
    expect(input('From').value).toBe('2026/10/05');
    fireEvent.change(input('To'), { target: { value: '2026/10/12' } });
    expect(onChange).toHaveBeenLastCalledWith({ from: '2026-10-05', to: '2026-10-12' });
  });

  it('picks a range with two clicks, in either order', () => {
    const onChange = vi.fn();
    render(<Range onChange={onChange} />);
    fireEvent.click(screen.getByRole('button', { name: 'Open calendar, Dates' }));
    const cal = screen.getByRole('dialog', { name: 'Choose date' });
    fireEvent.click(within(cal).getByRole('button', { name: /October 22nd, 2026/ }));
    expect(onChange).not.toHaveBeenCalled(); // the first click starts the range
    fireEvent.click(within(cal).getByRole('button', { name: /October 19th, 2026/ }));
    expect(onChange).toHaveBeenCalledWith({ from: '2026-10-19', to: '2026-10-22' });
    expect(input('From').value).toBe('2026/10/19');
    expect(input('To').value).toBe('2026/10/22');
  });
});
