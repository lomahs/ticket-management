import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { Schemas } from '../shared/api/types';
import { ReviewDialog } from '../features/ticket/components/ReviewDialog';
import { CommitDialog } from '../features/tickets/components/CommitDialog';

afterEach(cleanup);

const update: Schemas['TicketChangePreview'] = {
  key: 'PAY-1201', summary: '[PAY] Refund – Create', transition: null, jira_status: null, estimate_tracking: false, requests: [],
  changes: [{ field: 'remaining', jira_field: 'timeestimate', before: '2', after: '4' }, { field: 'estimate', jira_field: 'timeoriginalestimate', before: '3', after: '5' }],
};
const name = (id: string) => id;
const noop = () => {};

describe('the review of a Jira change', () => {
  it('shows the remaining estimate in hours, as the estimate', () => {
    render(<CommitDialog preview={{ creates: [], updates: [update] }} parentLabel={name} name={name} commit={noop} close={noop} />);
    const updates = screen.getByRole('region', { name: 'Updates' });
    expect(updates.textContent).toContain('Remaining 2.00h → 4.00h');
    expect(updates.textContent).toContain('Estimate 3.00h → 5.00h');
  });

  it('shows the remaining estimate in hours in the review of one ticket', () => {
    render(<ReviewDialog review={update} name={name} apply={noop} close={noop} />);
    const dialog = screen.getByRole('dialog', { name: 'Apply to Jira' });
    expect(within(dialog).getByText('Remaining estimate')).toBeTruthy();
    expect(within(dialog).getByText('2.00h')).toBeTruthy();
    expect(within(dialog).getByText('4.00h')).toBeTruthy();
  });
});
