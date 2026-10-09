import { expect, test } from '@playwright/test';

test('sidebar navigates between screens and browser history works', async ({ page }) => {
  await page.goto('/');
  await expect(page).toHaveURL(/\/my-work$/);
  await page.getByRole('link', { name: 'Function Matrix' }).click();
  await expect(page).toHaveURL(/\/anken\/PAY-1100\/matrix/);
  await page.getByRole('link', { name: 'Planning' }).click();
  await expect(page.getByRole('heading', { name: 'Planning' })).toBeVisible();
  await page.goBack();
  await expect(page.getByRole('heading', { name: 'Function Matrix' })).toBeVisible();
});

test('matrix summary filter lives in the URL and survives a reload', async ({ page }) => {
  await page.goto('/anken/PAY-1100/matrix');
  await page.getByRole('button', { name: 'Late', exact: true }).click();
  await expect(page).toHaveURL(/prog=late/);
  await expect(page.getByText('6/12 function')).toBeVisible();
  await page.reload();
  await expect(page.getByText('6/12 function')).toBeVisible();
});

test('a matrix cell opens the ticket panel and a status change is written to Jira', async ({ page }) => {
  await page.goto('/anken/PAY-1100/matrix');
  await page.getByRole('button', { name: /^PAY-1213 / }).click();
  await expect(page).toHaveURL(/ticket=PAY-1213/);
  const panel = page.getByRole('complementary', { name: 'Ticket PAY-1213' });
  await panel.getByLabel('Status').selectOption('R');
  await expect(page.getByRole('status').filter({ hasText: 'Written to Jira · PAY-1213 status: WIP → Resolved' })).toBeVisible();
});

test('dragging a Gantt block queues a change that can be pushed', async ({ page }) => {
  await page.goto('/planning?start=2026-10-05&weeks=2');
  const block = page.getByRole('button', { name: /^PAY-1262 / });
  const binh = page.getByLabel('Bình plan');
  const box = (await binh.boundingBox())!;
  await block.dragTo(binh, { targetPosition: { x: box.width * 0.62, y: 10 } });
  const tray = page.getByRole('region', { name: 'Changes pending push to Jira' });
  await expect(tray.getByText('1 change')).toBeVisible();
  await tray.getByRole('button', { name: 'Push to Jira' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Written to Jira · 1 plan change' })).toBeVisible();
});

test('leave can be added through its dialog URL', async ({ page }) => {
  await page.goto('/calendar/leave/new?month=2026-10&member=an&date=2026-10-19');
  const dialog = page.getByRole('dialog', { name: 'Add leave' });
  await dialog.getByLabel('To').fill('2026-10-21');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(page).toHaveURL(/\/calendar\?month=2026-10$/);
  await expect(page.getByRole('status').filter({ hasText: 'An · All day saved' })).toBeVisible();
});

test('the import wizard walks its steps by URL', async ({ page }) => {
  await page.goto('/calendar/import/2');
  await page.getByRole('button', { name: 'Use a sample file' }).click();
  await expect(page.getByText('10 rows read')).toBeVisible();
  await page.getByRole('button', { name: 'Next' }).click();
  await expect(page).toHaveURL(/\/calendar\/import\/3/);
  await expect(page.getByText(/with errors/)).toBeVisible();
  await page.getByRole('button', { name: 'Next' }).click();
  await page.getByRole('button', { name: /^Import \d+ rows$/ }).click();
  await expect(page).toHaveURL(/\/calendar\?month=2026-11/);
});

test('settings reject an unknown epic and preview name rules', async ({ page }) => {
  await page.goto('/settings/jira');
  await page.getByLabel('Epic key').fill('PAY-9999');
  await page.getByRole('button', { name: 'Add epic' }).click();
  await expect(page.getByRole('alert')).toHaveText('PAY-9999 was not found or is not an Epic');
  await page.getByRole('tab', { name: 'Name rules' }).click();
  await expect(page).toHaveURL(/\/settings\/name-rules$/);
  await expect(page.getByText(/synced sub-tasks match this pattern/)).toBeVisible();
});

test('dark theme toggles', async ({ page }) => {
  await page.goto('/my-work');
  await page.getByRole('button', { name: /Switch to (dark|light) theme/ }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', /dark|light/);
});
