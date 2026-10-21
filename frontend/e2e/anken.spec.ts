import { expect, test } from '@playwright/test';

test.describe('as a BA', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  test('BA sees only their anken', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('Username').fill('an');
    await page.getByLabel('Password').fill('demo');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page).toHaveURL(/\/my-work/);

    await page.goto('/anken');
    await expect(page.getByText('2 active anken')).toBeVisible();
    await expect(page.getByRole('button', { name: /PAY-1100/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /PAY-1300/ })).toBeVisible();

    await page.goto('/anken/CRM-400');
    await expect(page.getByText('Anken CRM-400 is not tracked')).toBeVisible();
  });
});

test('switcher narrows every screen', async ({ page }) => {
  await page.goto('/planning?start=2026-10-05&weeks=2');
  await expect(page.getByLabel('Bình plan')).toBeVisible();
  await page.getByLabel('Anken', { exact: true }).selectOption('PAY-1300');
  await expect(page.getByLabel('An plan')).toBeVisible();
  await expect(page.getByLabel('Bình plan')).toHaveCount(0);
  await expect(page.getByLabel('Dung plan')).toHaveCount(0);

  await page.reload();
  await expect(page.getByLabel('Anken', { exact: true })).toHaveValue('PAY-1300');
  await expect(page.getByLabel('Bình plan')).toHaveCount(0);
  // leave the shared browser state as the other specs expect it
  await page.getByLabel('Anken', { exact: true }).selectOption('');
  await expect(page.getByLabel('Bình plan')).toBeVisible();
});

test('PM adds a not-member', async ({ page }) => {
  // An works on CRM-400 but the demo makes them a member of PAY-1100 and PAY-1300 only.
  await page.goto('/anken/CRM-400');
  const warning = page.getByRole('region', { name: 'Working here but not a member' });
  const anRow = warning.locator('div').filter({ hasText: /^An — / });
  await expect(anRow).toHaveCount(1);
  await anRow.getByRole('button', { name: 'Add to anken' }).click();
  await expect(anRow).toHaveCount(0);
  await expect(page.getByRole('list', { name: 'Anken members' }).getByText('An', { exact: true })).toBeVisible();
});
