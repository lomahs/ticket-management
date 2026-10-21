import { expect, test } from '@playwright/test';

test('overdue count opens the ticket list', async ({ page }) => {
  await page.goto('/anken');
  const link = page.getByRole('link', { name: /^Overdue [1-9]\d*$/ }).first();
  await expect(link).toBeVisible();
  const n = Number((await link.textContent())!.replace(/\D/g, ''));
  await link.click();

  await expect(page).toHaveURL(/\/tickets\?.*label=overdue/);
  const rows = page.getByRole('list', { name: 'Tickets' }).getByRole('button');
  await expect(rows).toHaveCount(n);

  await rows.first().click();
  await expect(page.getByText('Finish: Overdue')).toBeVisible();
});
