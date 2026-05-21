import { test, expect } from '@playwright/test';

const API = 'http://localhost:8082/api';

test.describe('TDD Pipeline Toggle', () => {
  async function createSchema(page: any, name: string) {
    const resp = await page.request.post(`${API}/schemas`, {
      data: { name, description: 'TDD test', nodes: [], edges: [] }
    });
    return (await resp.json()).id;
  }

  async function getPipeline(page: any, schemaId: string) {
    const resp = await page.request.get(`${API}/schemas/${schemaId}`);
    return (await resp.json()).pipeline;
  }

  async function openPipelinePanel(page: any) {
    const btn = page.locator('button', { hasText: 'Pipeline' });
    await expect(btn).toBeVisible({ timeout: 15000 });
    await btn.click();
    await page.waitForTimeout(500);
  }

  async function navigateToStudio(page: any, schemaId: string) {
    await page.goto(`http://localhost:5173/app/${schemaId}`);
    await page.waitForLoadState('load');
    // Wait for the Studio to fully render (blueprint canvas or palette)
    await expect(page.locator('.blueprint-view, .block-palette, .pipeline-panel').first()).toBeVisible({ timeout: 15000 });
    await page.waitForTimeout(1000);
  }

  test('should create TDD-expanded pipeline when checkbox is checked', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('axolotl_token', 'e2e-test');
    });

    const schemaId = await createSchema(page, `pw-tdd-on-${Date.now()}`);
    await navigateToStudio(page, schemaId);
    await openPipelinePanel(page);

    // Verify TDD checkbox is visible when no pipeline exists
    const tddCheckbox = page.locator('.tdd-toggle input[type="checkbox"]');
    await expect(tddCheckbox).toBeVisible({ timeout: 5000 });
    await expect(tddCheckbox).not.toBeChecked();

    // Check the TDD toggle
    await tddCheckbox.check();
    await expect(tddCheckbox).toBeChecked();

    // Click Create Default and wait for pipeline content to appear
    const createBtn = page.locator('button', { hasText: 'Create Default' });
    await expect(createBtn).toBeVisible();
    await createBtn.click();
    await expect(page.locator('.pipeline-info')).toBeVisible({ timeout: 10000 });

    // Verify via API: 7 stages (source, review, test, verify-test, impl, verify, output)
    const pipeline = await getPipeline(page, schemaId);
    expect(pipeline).toBeDefined();
    expect(pipeline.stages).toBeDefined();
    expect(pipeline.stages.length).toBe(7);

    const stageNames = pipeline.stages.map((s: any) => s.id);
    expect(stageNames).toContain('test-think-1');
    expect(stageNames).toContain('verify-test-think-1');
    expect(stageNames).toContain('impl-think-1');
    expect(stageNames).toContain('verify-think-1');

    // Verify the panel shows TDD stage count and TDD badge
    const infoText = await page.locator('.pipeline-info').textContent();
    expect(infoText).toContain('7 stages');

    // Verify TDD badge is visible
    const tddBadge = page.locator('.tdd-badge');
    await expect(tddBadge).toBeVisible({ timeout: 5000 });
    await expect(tddBadge).toHaveText('TDD');

    // Verify stage tags exist on TDD-expanded stages
    const stageTags = page.locator('.stage-tag');
    await expect(stageTags.first()).toBeVisible({ timeout: 5000 });

    // Verify at least one test tag and one impl tag
    const testTag = page.locator('.tag-test');
    const implTag = page.locator('.tag-impl');
    await expect(testTag.first()).toBeVisible({ timeout: 5000 });
    await expect(implTag.first()).toBeVisible({ timeout: 5000 });
  });

  test('should create default 5-stage pipeline without TDD', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('axolotl_token', 'e2e-test');
    });

    const schemaId = await createSchema(page, `pw-tdd-off-${Date.now()}`);
    await navigateToStudio(page, schemaId);
    await openPipelinePanel(page);

    // TDD checkbox visible, leave unchecked
    const tddCheckbox = page.locator('.tdd-toggle input[type="checkbox"]');
    await expect(tddCheckbox).toBeVisible({ timeout: 5000 });
    await expect(tddCheckbox).not.toBeChecked();

    // Click Create Default without checking TDD, wait for pipeline content
    const createBtn = page.locator('button', { hasText: 'Create Default' });
    await expect(createBtn).toBeVisible();
    await createBtn.click();
    await expect(page.locator('.pipeline-info')).toBeVisible({ timeout: 10000 });

    // Verify via API: 5 stages (non-expanded)
    const pipeline = await getPipeline(page, schemaId);
    expect(pipeline).toBeDefined();
    expect(pipeline.stages).toBeDefined();
    expect(pipeline.stages.length).toBe(5);

    // No TDD stage names
    const stageNames = pipeline.stages.map((s: any) => s.id);
    expect(stageNames.some((n: string) => n.startsWith('test-'))).toBeFalsy();
    expect(stageNames.some((n: string) => n.startsWith('verify-test-'))).toBeFalsy();
    expect(stageNames.some((n: string) => n.startsWith('impl-'))).toBeFalsy();

    // Verify TDD badge is NOT shown for non-TDD pipeline
    await expect(page.locator('.tdd-badge')).not.toBeVisible();
  });
});
