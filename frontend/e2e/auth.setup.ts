import { expect, test as setup } from '@playwright/test';

/** Signs in as the demo admin once; the `app` project reuses the saved session. */
setup('sign in as minh', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Username').fill('minh');
  await page.getByLabel('Password').fill('demo');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/my-work/);
  await page.context().storageState({ path: '../.tmp/e2e/admin.json' });
});
