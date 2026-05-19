import { test as baseTest, expect } from '@playwright/test';

// ─── Helpers ─────────────────────────────────────────────────────────────

let realToken: string | null = null;
const createdSchemaIds: string[] = [];
const BASE_URL = 'http://localhost:8082';

function trackSchema(id: string) {
  createdSchemaIds.push(id);
}

async function createAuthHeaders() {
  return { Authorization: `Bearer ${realToken}` };
}

async function getSchema(request: any, schemaId: string) {
  const res = await request.get(`${BASE_URL}/api/schemas/${schemaId}`, {
    headers: await createAuthHeaders(),
  });
  return res.json();
}

async function setupAuth(page: any, useRealToken = false) {
  const token = useRealToken && realToken ? realToken : 'e2e-test-token';
  await page.addInitScript((t: string) => {
    localStorage.setItem('axolotl_token', t);
    localStorage.setItem('axolotl_username', 'testuser');
    localStorage.setItem('axolotl_role', 'user');
  }, token);
}

// ─── Schema Setup ────────────────────────────────────────────────────────

const NODE_IDS = {
  source: 'rc-source',
  review: 'rc-review',
};

const SCHEMA_PAYLOAD = {
  name: 'Studio Review Checks ' + Date.now(),
  targetPath: null,
  userId: null,
  nodes: [
    {
      id: NODE_IDS.source,
      type: 'source',
      name: 'Input',
      position: { x: 0, y: 200 },
      data: { config: { sourceType: 'text', sourceData: 'Test content' } },
    },
    {
      id: NODE_IDS.review,
      type: 'review',
      name: 'Review Plan',
      position: { x: 350, y: 200 },
      data: { config: { mode: 'manual', checks: {}, maxIterations: 3 } },
    },
  ],
  edges: [
    { source: NODE_IDS.source, target: NODE_IDS.review },
  ],
};

let schemaId: string;

baseTest.beforeAll(async ({ request }) => {
  // Login
  const loginRes = await request.post(`${BASE_URL}/api/auth/login`, {
    data: { username: 'playwright', password: 'test123' },
  });
  const loginData = await loginRes.json();
  realToken = loginData.token;
  expect(realToken).toBeTruthy();

  // Create schema
  const headers = await createAuthHeaders();
  const createRes = await request.post(`${BASE_URL}/api/schemas`, {
    data: { ...SCHEMA_PAYLOAD, id: crypto.randomUUID() },
    headers,
  });
  expect(createRes.ok()).toBeTruthy();
  const created = await createRes.json();
  schemaId = created.id;
  expect(schemaId).toBeTruthy();
  trackSchema(schemaId);
});

baseTest.afterAll(async ({ request }) => {
  if (!realToken || createdSchemaIds.length === 0) return;
  const headers = await createAuthHeaders();
  for (const id of createdSchemaIds) {
    try {
      await request.delete(`${BASE_URL}/api/schemas/${id}`, { headers });
    } catch {
      console.warn(`Failed to clean up schema ${id}`);
    }
  }
  createdSchemaIds.length = 0;
});

// ─── Tests ───────────────────────────────────────────────────────────────

baseTest.describe('Studio Review Checks Persistence', () => {
  // Verify auth works before test
  baseTest.beforeEach(async ({ page }) => {
    // Debug mode — log API requests during page load
    let apiCalls = 0;
    page.on('request', req => {
      if (req.url().includes(':8082/api/')) apiCalls++;
    });

    await setupAuth(page, true);

    // Navigate to schema page
    await page.goto(`/app/${schemaId}`);
    await expect(page.locator('.studio')).toBeVisible({ timeout: 15000 });

    // Confirm we're not on login (auth is working)
    const currentUrl = page.url();
    expect(currentUrl, `Expected URL to contain /app/, got ${currentUrl}`).toContain('/app/');

    // Small delay for Vue async ops
    await page.waitForTimeout(500);

    // Check store state
    const storeState = await page.evaluate(() => {
      const app = (document.querySelector('#app') as any)?.__vue_app__;
      if (!app?.config?.globalProperties?.$pinia) return 'no pinia';
      const pinia = app.config.globalProperties.$pinia;
      const schemaSt = pinia.state.value.schema;
      return JSON.stringify({
        schemaCount: schemaSt?.schemas?.length || 0,
        hasCurrentSchema: !!schemaSt?.currentSchema,
        nodeCount: schemaSt?.currentSchema?.nodes?.length || 0,
        currentId: schemaSt?.currentSchema?.id?.substring(0,12) || null,
      });
    });
    console.log(`[DEBUG] schema store: ${storeState}, apiCalls: ${apiCalls}`);

    // Wait for VueFlow nodes to appear
    await expect(page.locator('.vue-flow__node').first()).toBeVisible({ timeout: 15000 });
  });

  baseTest('should persist review check toggles, mode, and max iterations', { timeout: 60000 }, async ({ page }) => {
    // ─── Step 1: Click the Review node ─────────────────────────────────
    const reviewNode = page.locator(`.vue-flow__node[data-id="${NODE_IDS.review}"]`);
    await reviewNode.click({ force: true });
    await expect(page.locator('.config-panel')).toBeVisible({ timeout: 5000 });

    // ─── Step 2: Find checkboxes by label text ──────────────────────────
    const premortemCheck = page
      .locator('.config-panel .config-checkbox')
      .filter({ hasText: /Premortem/i })
      .locator('input[type="checkbox"]');
    const prismCheck = page
      .locator('.config-panel .config-checkbox')
      .filter({ hasText: /Prism/i })
      .locator('input[type="checkbox"]');
    const postmortemCheck = page
      .locator('.config-panel .config-checkbox')
      .filter({ hasText: /Postmortem/i })
      .locator('input[type="checkbox"]');

    // Wait for config panel to fully render
    await expect(premortemCheck.first()).toBeAttached({ timeout: 5000 });

    // ─── Step 3: Set checks as specified ────────────────────────────────
    // Premortem: DISABLE
    const premCount = await premortemCheck.count();
    if (premCount > 0) {
      if (await premortemCheck.isChecked()) {
        await premortemCheck.uncheck();
      }
    }
    await page.waitForTimeout(300);

    // Prism: ENABLE
    const prismCount = await prismCheck.count();
    if (prismCount > 0) {
      if (!(await prismCheck.isChecked())) {
        await prismCheck.check();
      }
    }
    await page.waitForTimeout(300);

    // Postmortem: ENABLE
    const postCount = await postmortemCheck.count();
    if (postCount > 0) {
      if (!(await postmortemCheck.isChecked())) {
        await postmortemCheck.check();
      }
    }
    await page.waitForTimeout(300);

    // ─── Step 4: Set mode to "hybrid" ───────────────────────────────────
    const modeSelect = page.locator('.config-panel select').filter({
      has: page.locator('option[value="hybrid"]'),
    });
    await modeSelect.selectOption('hybrid');
    await page.waitForTimeout(300);

    // ─── Step 5: Set Max Iterations ─────────────────────────────────────
    const maxIterInput = page.locator('.config-panel input[type="number"]').first();
    await maxIterInput.fill('7');
    await page.waitForTimeout(100);

    // Set up listener for the auto-save PUT (fires 2s after markDirty)
    // Must be set up BEFORE the debounce timer fires
    const autoSaveResp = page.waitForResponse(resp =>
      resp.url().includes('/api/schemas/' + schemaId) &&
      resp.request().method() === 'PUT'
    );

    // ─── Step 6: Wait for dirty flag debounce ───────────────────────────
    // Auto-save timer is 2s; the PUT should fire during this window
    await page.waitForTimeout(3000);

    // Confirm the save reached the backend before navigating
    await autoSaveResp;

    // ─── Step 7: Navigate to Dashboard ──────────────────────────────────
    await page.locator('.back-btn').click();
    await expect(page).toHaveURL('/');

    // ════════════════════════════════════════════════════════════════════
    // VERIFICATION ROUND 1: API
    // ════════════════════════════════════════════════════════════════════

    const schema = await getSchema(page.request, schemaId);
    const reviewNodeRaw = schema.nodes.find((n: any) => n.id === NODE_IDS.review);
    expect(reviewNodeRaw).toBeTruthy();

    const reviewData = reviewNodeRaw.data;
    expect(reviewData).toBeTruthy();

    const reviewConfig = reviewData.config || {};
    console.log('Review config after save:', JSON.stringify(reviewConfig, null, 2));

    // Verify mode
    expect(reviewConfig.mode).toBe('hybrid');

    // Verify max iterations
    expect(reviewConfig.maxIterations).toBe(7);

    // Verify check states (may be nested in config.checks)
    const checks = reviewConfig.checks || {};
    // premortem → false
    const premValue = checks.premortem ?? reviewConfig.premortem ?? true;
    expect(premValue).toBe(false);

    // prism → true
    const prismValue = checks.prism ?? reviewConfig.prism ?? false;
    expect(prismValue).toBe(true);

    // postmortem → true
    const postValue = checks.postmortem ?? reviewConfig.postmortem ?? false;
    expect(postValue).toBe(true);

    // ════════════════════════════════════════════════════════════════════
    // VERIFICATION ROUND 2: Navigate back and check UI
    // ════════════════════════════════════════════════════════════════════

    await page.goto(`/app/${schemaId}`);
    await expect(page.locator('.studio')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('.vue-flow__node').first()).toBeVisible({ timeout: 10000 });

    await expect(page.locator('.vue-flow__node').first()).toBeVisible({ timeout: 10000 });

    // Click Review node
    await reviewNode.click({ force: true });
    await expect(page.locator('.config-panel')).toBeVisible({ timeout: 5000 });

    // Wait for checkboxes
    await expect(premortemCheck.first()).toBeAttached({ timeout: 5000 });

    // Verify premortem is UNCHECKED
    if (await premortemCheck.count() > 0) {
      expect(await premortemCheck.isChecked()).toBe(false);
    }

    // Verify prism is CHECKED
    if (await prismCheck.count() > 0) {
      expect(await prismCheck.isChecked()).toBe(true);
    }

    // Verify postmortem is CHECKED
    if (await postmortemCheck.count() > 0) {
      expect(await postmortemCheck.isChecked()).toBe(true);
    }

    // Verify mode select shows "hybrid"
    await expect(modeSelect).toHaveValue('hybrid');

    // Verify max iterations shows 7
    await expect(maxIterInput).toHaveValue('7');
  });
});
