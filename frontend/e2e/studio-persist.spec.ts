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
  source: 'ps-source',
  agent: 'ps-agent',
  review: 'ps-review',
  verifier: 'ps-verifier',
  output: 'ps-output',
};

// Node creation payload — all 5 types, no edges, known internal IDs
const SCHEMA_PAYLOAD = {
  name: 'Studio Persist All Types ' + Date.now(),
  targetPath: null,
  userId: null,
  nodes: [
    {
      id: NODE_IDS.source,
      type: 'source',
      name: 'Input',
      position: { x: 0, y: 200 },
      data: {
        config: { sourceType: 'text', sourceData: 'Initial source content' },
      },
    },
    {
      id: NODE_IDS.agent,
      type: 'agent',
      name: 'Agent',
      position: { x: 350, y: 200 },
      data: { config: {} },
    },
    {
      id: NODE_IDS.review,
      type: 'review',
      name: 'Review Plan',
      position: { x: 700, y: 200 },
      data: { config: { mode: 'manual', checks: {}, maxIterations: 3 } },
    },
    {
      id: NODE_IDS.verifier,
      type: 'verifier',
      name: 'Verify Code',
      position: { x: 1050, y: 200 },
      data: { config: { checks: {} } },
    },
    {
      id: NODE_IDS.output,
      type: 'output',
      name: 'Final Output',
      position: { x: 1400, y: 200 },
      data: { config: {} },
    },
  ],
  edges: [],
};

// Will be set in beforeAll after POST creation
let schemaId: string;

baseTest.beforeAll(async ({ request }) => {
  // Login and get token
  const loginRes = await request.post(`${BASE_URL}/api/auth/login`, {
    data: { username: 'playwright', password: 'test123' },
  });
  const loginData = await loginRes.json();
  realToken = loginData.token;
  expect(realToken).toBeTruthy();

  // Create schema via POST (returns the server-assigned ID)
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

baseTest.describe('Studio Persist - All Node Types', () => {
  baseTest.beforeEach(async ({ page }) => {
    await setupAuth(page, true);
    await page.goto(`/app/${schemaId}`);
    await expect(page.locator('.studio')).toBeVisible({ timeout: 10000 });
    // Wait for VueFlow nodes to render on canvas
    await expect(page.locator('.vue-flow__node').first()).toBeVisible({ timeout: 10000 });
  });

  baseTest('should connect edges and edit all node types, persist after nav', { timeout: 60000 }, async ({ page }) => {
    // ─── Step 1: Connect edges ────────────────────────────────────────
    // VueFlow handles use .source (output) and .target (input) CSS classes
    const sourceHandle = page.locator(
      `.vue-flow__node[data-id="${NODE_IDS.source}"] .vue-flow__handle.source`
    );
    const agentTarget = page.locator(
      `.vue-flow__node[data-id="${NODE_IDS.agent}"] .vue-flow__handle.target`
    );
    const agentSource = page.locator(
      `.vue-flow__node[data-id="${NODE_IDS.agent}"] .vue-flow__handle.source`
    );
    const reviewTarget = page.locator(
      `.vue-flow__node[data-id="${NODE_IDS.review}"] .vue-flow__handle.target`
    );
    const reviewSource = page.locator(
      `.vue-flow__node[data-id="${NODE_IDS.review}"] .vue-flow__handle.source`
    );
    const verifierTarget = page.locator(
      `.vue-flow__node[data-id="${NODE_IDS.verifier}"] .vue-flow__handle.target`
    );
    const verifierSource = page.locator(
      `.vue-flow__node[data-id="${NODE_IDS.verifier}"] .vue-flow__handle.source`
    );
    const outputTarget = page.locator(
      `.vue-flow__node[data-id="${NODE_IDS.output}"] .vue-flow__handle.target`
    );

    // Drag source → agent
    await sourceHandle.dragTo(agentTarget, { force: true });
    // Drag agent → review
    await agentSource.dragTo(reviewTarget, { force: true });
    // Drag review → verifier
    await reviewSource.dragTo(verifierTarget, { force: true });
    // Drag verifier → output
    await verifierSource.dragTo(outputTarget, { force: true });
    // Wait for edge creation + syncFlowToStore API calls
    await page.waitForTimeout(3000);

    // ─── Step 2: Edit Agent node ──────────────────────────────────────
    const agentNode = page.locator(`.vue-flow__node[data-id="${NODE_IDS.agent}"]`);
    await agentNode.click();
    await expect(page.locator('.config-panel')).toBeVisible({ timeout: 5000 });

    // Wait for model options to load from provider API (option is inside optgroup — use toBeAttached)
    await expect(
      page.locator('.config-panel option[value="deepseek-v4-flash-free"]')
    ).toBeAttached({ timeout: 10000 });

    // Select model
    const modelSelect = page.locator('.config-panel .config-select').first();
    await modelSelect.selectOption('deepseek-v4-flash-free');
    await page.waitForTimeout(500);

    // Edit prompt
    const promptInput = page.locator('.config-panel .config-textarea--large');
    await expect(promptInput).toBeVisible({ timeout: 3000 });
    await promptInput.fill('Agent test prompt for persistence');
    await page.waitForTimeout(500);

    // ─── Step 3: Edit Review node ─────────────────────────────────────
    const reviewNode = page.locator(`.vue-flow__node[data-id="${NODE_IDS.review}"]`);
    await reviewNode.click();
    await expect(page.locator('.config-panel')).toBeVisible({ timeout: 5000 });

    // Wait for model options
    await expect(
      page.locator('.config-panel option[value="deepseek-v4-flash-free"]')
    ).toBeAttached({ timeout: 10000 });

    // Select model — it's the first select (.config-section > .config-select)
    const reviewModelSelect = page.locator('.config-panel .config-section > .config-select').first();
    await reviewModelSelect.selectOption('deepseek-v4-flash-free');
    await page.waitForTimeout(300);

    // Set mode to "auto" — mode select is the one that has option[value="auto"]
    // (model select options come from API, don't have value "auto")
    const reviewModeSelect = page.locator('.config-panel select').filter({
      has: page.locator('option[value="auto"]'),
    });
    await reviewModeSelect.selectOption('auto');
    await page.waitForTimeout(300);

    // Check premortem checkbox
    const premortemCheck = page
      .locator('.config-panel .config-checkbox')
      .filter({ hasText: 'Premortem' })
      .locator('input[type="checkbox"]');
    if (await premortemCheck.count() > 0) {
      const isPremortemChecked = await premortemCheck.isChecked();
      if (!isPremortemChecked) {
        await premortemCheck.check();
      }
    }
    await page.waitForTimeout(300);

    // Set max iterations — find number input associated with review config
    const maxIterInput = page
      .locator('.config-panel label')
      .filter({ hasText: /Max Iterations/i })
      .locator('..')
      .locator('input[type="number"]');
    if (await maxIterInput.count() > 0) {
      await maxIterInput.fill('5');
    } else {
      // Fallback: any number input in the panel
      const numInput = page.locator('.config-panel input[type="number"]').first();
      if (await numInput.count() > 0) {
        await numInput.fill('5');
      }
    }
    await page.waitForTimeout(300);

    // ─── Step 4: Edit Verifier node ───────────────────────────────────
    const verifierNode = page.locator(`.vue-flow__node[data-id="${NODE_IDS.verifier}"]`);
    await verifierNode.click();
    await expect(page.locator('.config-panel')).toBeVisible({ timeout: 5000 });

    // Wait for model options
    await expect(
      page.locator('.config-panel option[value="deepseek-v4-flash-free"]')
    ).toBeAttached({ timeout: 10000 });

    // Select model — first .config-section > .config-select
    const verModelSelect = page.locator('.config-panel .config-section > .config-select').first();
    await verModelSelect.selectOption('deepseek-v4-flash-free');
    await page.waitForTimeout(300);

    // Uncheck syntax check
    const syntaxCheck = page
      .locator('.config-panel .config-checkbox')
      .filter({ hasText: 'Syntax Check' })
      .locator('input[type="checkbox"]');
    const syntaxCount = await syntaxCheck.count();
    if (syntaxCount > 0) {
      const isSyntaxChecked = await syntaxCheck.isChecked();
      if (isSyntaxChecked) {
        await syntaxCheck.uncheck();
        await page.waitForTimeout(500);
      }
    }

    // Fill test command — text input near "Test Command" label
    const testCmdField = page
      .locator('.config-panel .config-field')
      .filter({ hasText: /Test Command/i })
      .locator('input[type="text"]');
    if (await testCmdField.count() > 0) {
      await testCmdField.fill('npm test -- --run');
    }
    await page.waitForTimeout(300);

    // Fill required patterns — textarea near "Required Patterns" label
    const patternField = page
      .locator('.config-panel .config-field')
      .filter({ hasText: /Required Patterns/i })
      .locator('textarea');
    if (await patternField.count() > 0) {
      await patternField.fill('validate\nrun\ntest');
    }
    await page.waitForTimeout(300);

    // ─── Step 5: Wait for dirty flag debounce + flush before nav ──────
    // Give the 2s debounce time to fire and the API call to complete
    await page.waitForTimeout(3000);

    // ─── Step 6: Navigate to Dashboard ────────────────────────────────
    await page.locator('.back-btn').click();
    await expect(page).toHaveURL('/');
    // The onDeactivated hook fires flushSave() — wait briefly for it to complete
    await page.waitForTimeout(2000);

    // ════════════════════════════════════════════════════════════════════
    // VERIFICATION ROUND 1: API call
    // ════════════════════════════════════════════════════════════════════

    const schema = await getSchema(page.request, schemaId);

    // Verify all 4 edges exist
    expect(schema.edges).toBeTruthy();
    expect(schema.edges.length).toBeGreaterThanOrEqual(4);

    const findEdge = (source: string, target: string) =>
      schema.edges.find((e: any) => e.source === source && e.target === target);

    expect(findEdge(NODE_IDS.source, NODE_IDS.agent)).toBeTruthy();
    expect(findEdge(NODE_IDS.agent, NODE_IDS.review)).toBeTruthy();
    expect(findEdge(NODE_IDS.review, NODE_IDS.verifier)).toBeTruthy();
    expect(findEdge(NODE_IDS.verifier, NODE_IDS.output)).toBeTruthy();

    const findNode = (id: string) => schema.nodes.find((n: any) => n.id === id);

    // Verify Agent node
    const agentData = findNode(NODE_IDS.agent)?.data;
    expect(agentData).toBeTruthy();
    expect(agentData.model).toBe('deepseek-v4-flash-free');
    const agentPrompt = agentData.prompt || agentData.systemPrompt;
    expect(agentPrompt).toBe('Agent test prompt for persistence');

    // Verify Review node
    const reviewNodeRaw = findNode(NODE_IDS.review);
    expect(reviewNodeRaw).toBeTruthy();
    const reviewData = reviewNodeRaw.data;
    expect(reviewData).toBeTruthy();
    expect(reviewData.model).toBe('deepseek-v4-flash-free');
    // Review config lives in data.config
    const reviewConfig = reviewData.config || {};
    expect(reviewConfig.mode).toBe('auto');
    // Checks may be nested inside config.checks
    const reviewChecks = reviewConfig.checks || {};
    const premortemValue = reviewChecks.premortem ?? reviewConfig.premortem ?? false;
    expect(premortemValue).toBe(true);
    const maxIterValue = reviewConfig.maxIterations ?? reviewChecks.maxIterations ?? 0;
    expect(maxIterValue).toBe(5);

    // Verify Verifier node
    const verifierNodeRaw = findNode(NODE_IDS.verifier);
    expect(verifierNodeRaw).toBeTruthy();
    const verifierData = verifierNodeRaw.data;
    expect(verifierData).toBeTruthy();
    expect(verifierData.model).toBe('deepseek-v4-flash-free');
    const verConfig = verifierData.config || {};
    const verChecks = verConfig.checks || {};
    const syntaxValue = verChecks.syntaxCheck ?? verConfig.syntaxCheck ?? true;
    expect(syntaxValue).toBe(false);
    const testCmdValue = verChecks.testCommand ?? verConfig.testCommand ?? '';
    expect(testCmdValue).toBe('npm test -- --run');

    // Verify Source node unchanged
    const sourceData = findNode(NODE_IDS.source)?.data;
    expect(sourceData).toBeTruthy();
    expect(sourceData.config?.sourceData).toBe('Initial source content');

    // Verify Output node exists
    const outputNodeRaw = findNode(NODE_IDS.output);
    expect(outputNodeRaw).toBeTruthy();
    expect(outputNodeRaw.type).toBe('output');

    // ════════════════════════════════════════════════════════════════════
    // VERIFICATION ROUND 2: Navigate back and check UI
    // ════════════════════════════════════════════════════════════════════

    await page.goto(`/app/${schemaId}`);
    await expect(page.locator('.studio')).toBeVisible({ timeout: 10000 });
    // Wait for VueFlow to render all nodes
    await expect(page.locator('.vue-flow__node').first()).toBeVisible({ timeout: 10000 });
    // Wait for edges to render
    await expect(page.locator('.vue-flow__edge').first()).toBeVisible({ timeout: 5000 });

    // Verify edge count in UI
    const edgeCount = await page.locator('.vue-flow__edge').count();
    expect(edgeCount).toBeGreaterThanOrEqual(4);

    // Check Agent node UI
    await agentNode.click();
    await expect(page.locator('.config-panel')).toBeVisible({ timeout: 5000 });
    const agentModelAfter = page.locator('.config-panel .config-select').first();
    await expect(agentModelAfter).toHaveValue('deepseek-v4-flash-free');
    const promptAfter = page.locator('.config-panel .config-textarea--large');
    await expect(promptAfter).toHaveValue('Agent test prompt for persistence');

    // Check Review node UI (force to bypass config panel overlay)
    await reviewNode.click({ force: true });
    await expect(page.locator('.config-panel')).toBeVisible({ timeout: 5000 });
    // Model should persist
    const reviewModelAfter = page.locator('.config-panel .config-select').first();
    await expect(reviewModelAfter).toHaveValue('deepseek-v4-flash-free');

    // Check Verifier node UI (force to bypass config panel overlay)
    await verifierNode.click({ force: true });
    await expect(page.locator('.config-panel')).toBeVisible({ timeout: 5000 });
    // Model should persist
    const verModelAfter = page.locator('.config-panel .config-select').first();
    await expect(verModelAfter).toHaveValue('deepseek-v4-flash-free');
  });
});
