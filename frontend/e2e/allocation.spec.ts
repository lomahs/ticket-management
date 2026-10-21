import { expect, test } from '@playwright/test';

test('PM changes an allocation', async ({ page }) => {
  await page.goto('/anken/PAY-1100');
  await page.getByRole('button', { name: 'Allocation… An' }).click();
  const dialog = page.getByRole('dialog', { name: 'Allocation · An' });
  await expect(dialog.getByText('Total 5h of 8h').or(dialog.getByText('Total 8h of 8h'))).toBeVisible();
  await dialog.getByLabel('From').fill('2026-10-08');
  await dialog.getByLabel('PAY-1100').fill('4');
  await dialog.getByLabel('PAY-1300').fill('4');
  await expect(dialog.getByText('Total 8h of 8h')).toBeVisible();
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Allocation saved.' })).toBeVisible();
  await expect(dialog.getByRole('cell', { name: '2026-10-08' })).toBeVisible();
});

test('timesheet compares with allocation', async ({ page }) => {
  await page.goto('/effort/timesheet?from=2026-10-01&to=2026-10-07');
  await page.getByLabel('Anken', { exact: true }).selectOption('PAY-1100');
  // the change above starts on 10-08, so these days still carry the demo's 5h on PAY-1100
  await expect(page.getByTitle(/^An · .*target 5h/).first()).toBeVisible();
  // leave the shared browser state as the other specs expect it
  await page.getByLabel('Anken', { exact: true }).selectOption('');
});
