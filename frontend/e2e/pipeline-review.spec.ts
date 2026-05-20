import { test, expect } from '@playwright/test';

const API = 'http://localhost:8082/api';

test.describe('Pipeline Review Dialog', () => {
  test('should pause at review and show plan', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('axolotl_token', 'e2e-test');
      localStorage.setItem('axolotl_username', 'admin');
    });

    const schemaResp = await page.request.post(`${API}/schemas`, {
      data: { name: `pw-review-${Date.now()}`, description: 'Test plan in dialog', nodes: [], edges: [] }
    });
    const schemaId = (await schemaResp.json()).id;
    console.log('Schema:', schemaId);

    await page.request.post(`${API}/schemas/${schemaId}/pipeline/default`, { data: {} });
    console.log('Pipeline created');

    // Use correct route: /app/:id
    await page.goto(`http://localhost:5173/app/${schemaId}`);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(3000);
    console.log('Navigated to app path');

    // Toggle pipeline sidebar
    const pipelineBtn = page.locator('button', { hasText: 'Pipeline' });
    await expect(pipelineBtn).toBeVisible({ timeout: 5000 });
    await pipelineBtn.click();
    await page.waitForTimeout(500);
    console.log('Pipeline panel opened');

    // Click Execute
    const execBtn = page.locator('button', { hasText: 'Execute Pipeline' });
    await expect(execBtn).toBeVisible({ timeout: 5000 });
    await execBtn.click();
    console.log('Execute clicked');

    // Wait for review dialog
    const dialog = page.locator('.review-overlay');
    await expect(dialog).toBeVisible({ timeout: 60000 });
    console.log('Dialog opened');

    // Check plan
    const plan = dialog.locator('.plan-text');
    await expect(plan).toBeVisible({ timeout: 5000 });
    const text = await plan.textContent();
    console.log('Plan:', JSON.stringify(text?.slice(0, 200)));
    expect(text?.trim().length).toBeGreaterThan(0);

    // Accept
    await dialog.locator('.btn-approve').click();
    console.log('Accepted');

    // Wait for completion
    await page.waitForTimeout(20000);
    const runs = await (await page.request.get(`${API}/schemas/${schemaId}/runs`)).json();
    const last = Array.isArray(runs) ? runs[0] : runs;
    expect(last?.status).toBe('completed');
    console.log('Completed');
  });
});
