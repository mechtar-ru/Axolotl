import { test, expect } from '@playwright/test';

test.describe('Workflow Canvas', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
  });

  test('should load the canvas', async ({ page }) => {
    await expect(page.locator('.vue-flow')).toBeVisible();
  });

  test('should display schema name', async ({ page }) => {
    const schemaTitle = page.locator('.schema-title');
    await expect(schemaTitle).toBeVisible();
  });

  test('should show toolbar with node buttons', async ({ page }) => {
    await expect(page.getByRole('button', { name: /Source/i })).toBeVisible();
    await expect(page.getByRole('button', { name: /Agent/i })).toBeVisible();
    await expect(page.getByRole('button', { name: /Condition/i })).toBeVisible();
  });

  test('should add a source node', async ({ page }) => {
    await page.getByRole('button', { name: /Source/i }).click();
    const nodes = page.locator('.vue-flow__node');
    await expect(nodes.first()).toBeVisible();
  });

  test('should add an agent node', async ({ page }) => {
    await page.getByRole('button', { name: /Agent/i }).click();
    const nodes = page.locator('.vue-flow__node');
    await expect(nodes.first()).toBeVisible();
  });

  test('should show execution mode selector', async ({ page }) => {
    const modeSelector = page.locator('.mode-selector');
    await expect(modeSelector).toBeVisible();
    await expect(modeSelector).toHaveValue('EXECUTE');
  });

  test('should change execution mode', async ({ page }) => {
    const modeSelector = page.locator('.mode-selector');
    await modeSelector.selectOption('ANALYZE');
    await expect(modeSelector).toHaveValue('ANALYZE');
  });

  test('should show save button', async ({ page }) => {
    await expect(page.getByRole('button', { name: /Сохранить/i })).toBeVisible();
  });
});

test.describe('Login', () => {
  test('should show login page', async ({ page }) => {
    await page.goto('/login');
    await expect(page.locator('input[type="text"], input[type="email"]')).toBeVisible();
  });

  test('should login with credentials', async ({ page }) => {
    await page.goto('/login');
    await page.fill('input[type="text"], input[type="email"]', 'testuser');
    await page.fill('input[type="password"]', 'testpass');
    await page.click('button[type="submit"]');
    await expect(page).toHaveURL('/');
  });
});
