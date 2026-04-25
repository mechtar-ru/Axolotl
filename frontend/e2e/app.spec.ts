import { test, expect } from '@playwright/test';

// Helper: bypass login by setting token in localStorage
async function loginAs(page: any, username = 'admin') {
  await page.goto('/');
  await page.evaluate((u) => {
    localStorage.setItem('axolotl_token', 'test-token');
    localStorage.setItem('axolotl_username', u);
  }, username);
}

test.describe('Login', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
  });

  test('should redirect to /login when not authenticated', async ({ page }) => {
    await expect(page).toHaveURL(/\/login/);
  });

  test('should show login form', async ({ page }) => {
    await expect(page.locator('.login-card')).toBeVisible();
    await expect(page.locator('input[type="text"]')).toBeVisible();
    await expect(page.locator('input[type="password"]')).toBeVisible();
    await expect(page.locator('.login-btn')).toBeVisible();
  });

  test('should show default credentials hint on failed login', async ({ page }) => {
    await page.fill('input[type="text"]', 'wrong');
    await page.fill('input[type="password"]', 'wrong');
    await page.click('.login-btn');
    await expect(page.locator('.login-error')).toBeVisible();
    await expect(page.locator('.login-hint')).toContainText('admin');
  });
});

test.describe('Home (authenticated)', () => {
  test.beforeEach(async ({ page }) => {
    await loginAs(page);
    await page.goto('/');
  });

  test('should show empty state when no schema selected', async ({ page }) => {
    await expect(page.locator('.empty-state')).toBeVisible();
    await expect(page.locator('.empty-state__title')).toContainText('оркестрации');
  });

  test('should show templates on empty state', async ({ page }) => {
    await expect(page.locator('.template-card').first()).toBeVisible();
  });

  test('should show sidebar with schema list', async ({ page }) => {
    await expect(page.locator('.sidebar')).toBeVisible();
    await expect(page.locator('.sidebar-brand')).toContainText('Axolotl');
  });

  test('should show new schema button', async ({ page }) => {
    await expect(page.locator('.icon-btn[title="Новая схема"]')).toBeVisible();
  });

  test('should create new schema via sidebar button', async ({ page }) => {
    await page.locator('.icon-btn[title="Новая схема"]').click();
    // Should navigate to schema view and show canvas
    await expect(page.locator('.schema-title')).toBeVisible();
  });

  test('should show logout button in sidebar footer', async ({ page }) => {
    await expect(page.locator('.logout-btn')).toContainText('Выйти');
  });
});

test.describe('Workflow Canvas', () => {
  test.beforeEach(async ({ page }) => {
    await loginAs(page);
    await page.goto('/');
    // Create a schema to open the canvas
    await page.locator('.icon-btn[title="Новая схема"]').click();
    await expect(page.locator('.schema-title')).toBeVisible();
  });

  test('should show vue-flow canvas', async ({ page }) => {
    await expect(page.locator('.vue-flow')).toBeVisible();
  });

  test('should display schema name in header', async ({ page }) => {
    const schemaTitle = page.locator('.schema-title');
    await expect(schemaTitle).toBeVisible();
    await expect(schemaTitle).toContainText('Новая схема');
  });

  test('should show toolbar with add button', async ({ page }) => {
    await expect(page.locator('.toolbar-panel')).toBeVisible();
    await expect(page.locator('.toolbar-add-btn')).toBeVisible();
  });

  test('should open add-node dropdown and list all node types', async ({ page }) => {
    await page.locator('.toolbar-add-btn').click();
    const dropdown = page.locator('.add-dropdown');
    await expect(dropdown).toBeVisible();
    await expect(dropdown.locator('button', { hasText: 'Source' })).toBeVisible();
    await expect(dropdown.locator('button', { hasText: 'Agent' })).toBeVisible();
    await expect(dropdown.locator('button', { hasText: 'Condition' })).toBeVisible();
    await expect(dropdown.locator('button', { hasText: 'Output' })).toBeVisible();
    await expect(dropdown.locator('button', { hasText: 'Memory' })).toBeVisible();
    await expect(dropdown.locator('button', { hasText: 'Guardrail' })).toBeVisible();
    await expect(dropdown.locator('button', { hasText: 'Human' })).toBeVisible();
    await expect(dropdown.locator('button', { hasText: 'Loop' })).toBeVisible();
    await expect(dropdown.locator('button', { hasText: 'Fallback' })).toBeVisible();
    await expect(dropdown.locator('button', { hasText: 'Subagent' })).toBeVisible();
    await expect(dropdown.locator('button', { hasText: 'SchemaBuilder' })).toBeVisible();
    await expect(dropdown.locator('button', { hasText: 'Заметка' })).toBeVisible();
  });

  test('should add a source node via dropdown', async ({ page }) => {
    await page.locator('.toolbar-add-btn').click();
    await page.locator('.add-dropdown button', { hasText: 'Source' }).click();
    const nodes = page.locator('.vue-flow__node');
    await expect(nodes.first()).toBeVisible();
  });

  test('should add an agent node via dropdown', async ({ page }) => {
    await page.locator('.toolbar-add-btn').click();
    await page.locator('.add-dropdown button', { hasText: 'Agent' }).click();
    const nodes = page.locator('.vue-flow__node');
    await expect(nodes.first()).toBeVisible();
  });

  test('should show execution mode selector', async ({ page }) => {
    const modeSelector = page.locator('.mode-selector');
    await expect(modeSelector).toBeVisible();
    await expect(modeSelector).toHaveValue('EXECUTE');
  });

  test('should change execution mode to ANALYZE', async ({ page }) => {
    const modeSelector = page.locator('.mode-selector');
    await modeSelector.selectOption('ANALYZE');
    await expect(modeSelector).toHaveValue('ANALYZE');
    await expect(page.locator('.run-schema-btn')).toContainText('Анализ');
  });

  test('should change execution mode to DRY_RUN', async ({ page }) => {
    const modeSelector = page.locator('.mode-selector');
    await modeSelector.selectOption('DRY_RUN');
    await expect(page.locator('.run-schema-btn')).toContainText('Симуляция');
  });

  test('should show save and run buttons', async ({ page }) => {
    await expect(page.locator('.save-schema-btn')).toContainText('Сохранить');
    await expect(page.locator('.run-schema-btn')).toBeVisible();
    await expect(page.locator('.export-schema-btn')).toContainText('Экспорт');
    await expect(page.locator('.delete-schema-btn')).toBeVisible();
  });

  test('should show group/ungroup buttons', async ({ page }) => {
    await expect(page.locator('.toolbar-btn', { hasText: 'Группа' })).toBeVisible();
    await expect(page.locator('.toolbar-btn', { hasText: 'Разгрупп.' })).toBeVisible();
  });

  test('should show utility toolbar buttons', async ({ page }) => {
    await expect(page.locator('.toolbar-btn[title="История выполнений"]')).toBeVisible();
    await expect(page.locator('.toolbar-btn[title="Граф памяти"]')).toBeVisible();
    await expect(page.locator('.toolbar-btn[title="Сохранить как PNG"]')).toBeVisible();
  });

  test('should show model selector in toolbar', async ({ page }) => {
    await expect(page.locator('.toolbar-model-select')).toBeVisible();
    await expect(page.locator('.toolbar-model-select')).toHaveValue('');
  });

  test('should add multiple nodes', async ({ page }) => {
    // Add source
    await page.locator('.toolbar-add-btn').click();
    await page.locator('.add-dropdown button', { hasText: 'Source' }).click();
    // Add agent
    await page.locator('.toolbar-add-btn').click();
    await page.locator('.add-dropdown button', { hasText: 'Agent' }).click();
    // Add output
    await page.locator('.toolbar-add-btn').click();
    await page.locator('.add-dropdown button', { hasText: 'Output' }).click();

    const nodes = page.locator('.vue-flow__node');
    await expect(nodes).toHaveCount(3);
  });
});

test.describe('Templates', () => {
  test.beforeEach(async ({ page }) => {
    await loginAs(page);
    await page.goto('/');
  });

  test('should create schema from AI Pipeline template', async ({ page }) => {
    await page.locator('.template-card', { hasText: 'AI Pipeline' }).click();
    await expect(page.locator('.schema-title')).toBeVisible();
    // Should have 3 nodes (source, agent, output)
    await expect(page.locator('.vue-flow__node')).toHaveCount(3);
  });

  test('should create schema from RAG template', async ({ page }) => {
    await page.locator('.template-card', { hasText: 'RAG' }).click();
    await expect(page.locator('.schema-title')).toBeVisible();
    await expect(page.locator('.vue-flow__node')).toHaveCount(4);
  });

  test('should create schema from Condition template', async ({ page }) => {
    await page.locator('.template-card', { hasText: 'Condition Branch' }).click();
    await expect(page.locator('.schema-title')).toBeVisible();
    await expect(page.locator('.vue-flow__node')).toHaveCount(5);
  });
});

test.describe('Settings', () => {
  test.beforeEach(async ({ page }) => {
    await loginAs(page);
  });

  test('should navigate to settings', async ({ page }) => {
    await page.goto('/');
    await page.locator('.sidebar-footer-btn', { hasText: 'Настройки' }).click();
    await expect(page).toHaveURL(/\/settings/);
  });
});
