import { expect, test, type Page } from '@playwright/test';

test.use({ storageState: { cookies: [], origins: [] } });

async function signIn(page: Page, username: string, password = 'demo') {
  await page.goto('/login');
  await page.getByLabel('Username').fill(username);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

test('stranger requests access and the admin approves', async ({ page, browser }) => {
  await signIn(page, 'lan');
  await expect(page.getByText("You don't have access to Team Tracker yet.")).toBeVisible();
  await page.getByRole('button', { name: 'Request access' }).click();
  await expect(page.getByText('Your request was sent')).toBeVisible();

  const admin = await browser.newContext({ storageState: '../.tmp/e2e/admin.json' });
  const adminPage = await admin.newPage();
  await adminPage.goto('/admin/users');
  await expect(adminPage.getByRole('link', { name: /Users/ }).getByText('1', { exact: true })).toBeVisible();
  await adminPage.getByRole('button', { name: 'Approve…' }).click();
  const dialog = adminPage.getByRole('dialog');
  await dialog.getByLabel('Role').selectOption('PIM');
  await dialog.getByRole('button', { name: 'Approve', exact: true }).click();
  await expect(dialog).toBeHidden();
  await admin.close();

  await page.reload();
  await expect(page).toHaveURL(/\/my-work/);
});

test('BA sees the matrix read-only', async ({ page }) => {
  await signIn(page, 'an');
  await expect(page).toHaveURL(/\/my-work/);
  await page.goto('/anken/PAY-1100/matrix');
  await expect(page.getByRole('heading', { name: 'Function Matrix' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Settings' })).toHaveCount(0);
  await page.getByRole('button', { name: /^PAY-1213 / }).click();
  const panel = page.getByRole('complementary', { name: 'Ticket PAY-1213' });
  await expect(panel.getByLabel('Status')).toBeDisabled();
  await expect(panel.getByText(/You have view-only access/)).toBeVisible();
});

test('wrong password shows an error', async ({ page }) => {
  await signIn(page, 'an', 'nope');
  await expect(page.getByRole('alert')).toHaveText('Wrong username or password');
});
