import { test as baseTest, expect } from '@playwright/test';

// ─── Auth Setup ───────────────────────────────────────────────────────
// Route guard checks localStorage.getItem('axolotl_token') directly.
// For tests that need real API auth, we get a token from the backend.
// For tests that only check UI presence, any non-null token works.

let realToken: string | null = null;
let firstSchemaId: string | null = null;
const createdSchemaIds: string[] = [];

/** Register a schema ID for cleanup after all tests run. */
function trackSchema(id: string) {
  createdSchemaIds.push(id);
}

baseTest.beforeAll(async ({ request }) => {
  // Get real token for authenticated tests
  try {
    const res = await request.post('http://localhost:8082/api/auth/login', {
      data: { username: 'playwright', password: 'test123' },
    });
    const data = await res.json();
    if (data.token) realToken = data.token;

    // Get first schema ID for studio tests
    const schemasRes = await request.get('http://localhost:8082/api/schemas', {
      headers: { Authorization: `Bearer ${data.token}` },
    });
    const schemas = await schemasRes.json();
    if (Array.isArray(schemas) && schemas.length > 0) {
      firstSchemaId = schemas[0].id;
    }
  } catch {
    console.warn('Could not get auth token — some tests may fail');
  }
});

baseTest.afterAll(async ({ request }) => {
  if (!realToken || createdSchemaIds.length === 0) return;
  const headers = { Authorization: `Bearer ${realToken}` };
  for (const id of createdSchemaIds) {
    try {
      await request.delete(`http://localhost:8082/api/schemas/${id}`, { headers });
    } catch {
      console.warn(`Failed to clean up schema ${id}`);
    }
  }
  createdSchemaIds.length = 0;
});

// Helper: sets up auth via addInitScript (runs before Vue initializes)
async function setupAuth(page: any, useRealToken = false) {
  const token = useRealToken && realToken ? realToken : 'e2e-test-token';
  await page.addInitScript((t: string) => {
    localStorage.setItem('axolotl_token', t);
    localStorage.setItem('axolotl_username', 'testuser');
    localStorage.setItem('axolotl_role', 'user');
  }, token);
}

// ─── Login ────────────────────────────────────────────────────────────
baseTest.describe('Login', () => {
  baseTest.beforeEach(async ({ page }) => {
    await page.goto('/login');
  });

  baseTest('should redirect to /login when not authenticated', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveURL(/\/login/);
  });

  baseTest('should show login form', async ({ page }) => {
    await expect(page.locator('.login-card')).toBeVisible();
    await expect(page.locator('input[type="text"]')).toBeVisible();
    await expect(page.locator('input[type="password"]')).toBeVisible();
    await expect(page.locator('.login-btn')).toBeVisible();
  });

  baseTest('should show error on failed login', async ({ page }) => {
    await page.locator('input[type="text"]').fill('wrong');
    await page.locator('input[type="password"]').fill('wrong');
    await page.locator('.login-btn').click();
    // BCrypt + Neo4j lookup can take 20s+ for failed logins under load
    await expect(page.locator('.login-error')).toBeVisible({ timeout: 35000 });
    await expect(page.locator('.login-hint')).toContainText('admin', { timeout: 10000 });
  });

  baseTest('should log in with admin/admin and redirect to dashboard', async ({ page }) => {
    await page.locator('input[type="text"]').fill('admin');
    await page.locator('input[type="password"]').fill('admin');
    await page.locator('.login-btn').click();
    // BCrypt + Neo4j can be slow under load; .navbar appears after login redirects
    await expect(page.locator('.navbar')).toBeVisible({ timeout: 35000 });
    await expect(page.locator('.nav-username')).toContainText('admin');
  });
});

// ─── Dashboard ────────────────────────────────────────────────────────
baseTest.describe('Dashboard', () => {
  baseTest.beforeEach(async ({ page }) => {
    await setupAuth(page, true);
    await page.goto('/');
    await expect(page.locator('.navbar')).toBeVisible({ timeout: 10000 });
  });

  baseTest('should show navbar with brand', async ({ page }) => {
    await expect(page.locator('.nav-brand')).toContainText('Axolotl');
  });

  baseTest('should show navigation links', async ({ page }) => {
    await expect(page.locator('.nav-link', { hasText: 'Dashboard' })).toBeVisible();
    await expect(page.locator('.nav-link', { hasText: 'About' })).toBeVisible();
    await expect(page.locator('.nav-link', { hasText: 'Settings' })).toBeVisible();
  });

  baseTest('should show dashboard header', async ({ page }) => {
    await expect(page.locator('.dashboard')).toBeVisible();
    await expect(page.locator('.dashboard-header h1')).toContainText('Axolotl Studio');
    await expect(page.locator('.btn-primary')).toContainText('New App');
  });

  baseTest('should show search bar', async ({ page }) => {
    await expect(page.locator('.search-bar')).toBeVisible();
    await expect(page.locator('.search-input')).toBeVisible();
    await expect(page.locator('.search-input')).toHaveAttribute('placeholder', 'Search apps...');
  });

  baseTest('should show My Apps section and Templates section', async ({ page }) => {
    await expect(page.locator('.apps-section')).toBeVisible();
    await expect(page.locator('.templates-section')).toBeVisible();
  });

  baseTest('should show template cards', async ({ page }) => {
    const templates = page.locator('.template-card');
    await expect(templates.first()).toBeVisible();
    // Verify at least 3 of the known templates exist
    await expect(page.locator('.template-name', { hasText: 'Chat Bot' })).toBeVisible();
    await expect(page.locator('.template-name', { hasText: 'Blank App' })).toBeVisible();
  });

  baseTest('should show username and sign out button', async ({ page }) => {
    await expect(page.locator('.nav-username')).toBeVisible();
    await expect(page.locator('.nav-logout')).toContainText('Sign out');
  });

  baseTest('should open new app modal on New App click', async ({ page }) => {
    await page.locator('.btn-primary').click();
    // AppModal renders .app-modal-content (no .modal-overlay or .modal classes)
    await expect(page.locator('.app-modal-content')).toBeVisible();
    await expect(page.locator('.app-modal-header h3')).toContainText('Create New App');
    // Check modal has name input, type select, and action buttons
    await expect(page.locator('.app-modal-content .input[type="text"]')).toBeVisible();
    await expect(page.locator('.app-modal-content select.input')).toBeVisible();
    await expect(page.locator('.app-modal-content .btn-secondary')).toContainText('Cancel');
    await expect(page.locator('.app-modal-content .btn-primary')).toContainText('Create');
    // Close modal — use locator scoped to the modal
    await page.locator('.app-modal-content .btn-secondary').click();
    await expect(page.locator('.app-modal-content')).not.toBeVisible();
  });

  baseTest('should navigate to settings via nav link', async ({ page }) => {
    await page.locator('.nav-link', { hasText: 'Settings' }).click();
    await expect(page).toHaveURL(/\/settings/);
  });
});

// ─── Settings Model Toggles ────────────────────────────────────────────
baseTest.describe('Settings - Model Toggles', () => {
  baseTest.beforeEach(async ({ page }) => {
    await setupAuth(page, true);
    await page.goto('/settings');
    await expect(page.locator('.settings-page')).toBeVisible({ timeout: 10000 });
  });

  baseTest('should show model groups with collapsible headers', async ({ page }) => {
    // Wait for Zen provider card to expand and show model toggles
    const zenProvider = page.locator('.provider-card').filter({ hasText: 'zen' });
    await expect(zenProvider).toBeVisible({ timeout: 10000 });
    // Ensure provider is expanded (click header if collapsed)
    const header = zenProvider.locator('.provider-header');
    const chevron = header.locator('.collapse-chevron');
    const isRotated = await chevron.evaluate(el => el.classList.contains('rotated'));
    if (!isRotated) {
      await header.click();
      await expect(chevron).toHaveClass(/rotated/);
    }

    // Now verify model-group-headers exist inside the provider
    const headers = zenProvider.locator('.model-group-header');
    await expect(headers.first()).toBeVisible({ timeout: 10000 });
    // Expect at least 6 model groups (Claude, Gemini, GPT, Free, DeepSeek, etc.)
    await expect(headers).toHaveCount(9);
  });

  baseTest('should show correct group counts', async ({ page }) => {
    const zenProvider = page.locator('.provider-card').filter({ hasText: 'zen' });
    await expect(zenProvider).toBeVisible({ timeout: 10000 });
    // Expand provider
    const header = zenProvider.locator('.provider-header');
    const chevron = header.locator('.collapse-chevron');
    const isRotated = await chevron.evaluate(el => el.classList.contains('rotated'));
    if (!isRotated) {
      await header.click();
      await expect(chevron).toHaveClass(/rotated/);
    }

    // Check group counts have numbers
    const counts = zenProvider.locator('.model-group-count');
    const firstCount = await counts.first().textContent();
    expect(firstCount).toMatch(/\d+ \/ \d+/);
  });

  baseTest('should expand models when clicking a collapsed group', async ({ page }) => {
    const zenProvider = page.locator('.provider-card').filter({ hasText: 'zen' });
    await expect(zenProvider).toBeVisible({ timeout: 10000 });
    // Expand provider
    const provHeader = zenProvider.locator('.provider-header');
    const chevron = provHeader.locator('.collapse-chevron');
    const isRotated = await chevron.evaluate(el => el.classList.contains('rotated'));
    if (!isRotated) {
      await provHeader.click();
      await expect(chevron).toHaveClass(/rotated/);
    }

    // Find a collapsed group header — click it to open
    const collapsedHeader = zenProvider.locator('.model-group-header').filter({ hasText: 'Claude' }).first();
    await expect(collapsedHeader).toBeVisible({ timeout: 5000 });

    // Count current model-toggles (from already-open groups like Gemini)
    const beforeCount = await zenProvider.locator('.model-toggle').count();

    // Click to expand Claude group
    await collapsedHeader.click();

    // Wait for Claude models to appear — count should increase
    await expect(async () => {
      const afterCount = await zenProvider.locator('.model-toggle').count();
      expect(afterCount).toBeGreaterThan(beforeCount);
    }).toPass({ timeout: 5000 });
  });

  baseTest('should filter models via search', async ({ page }) => {
    const zenProvider = page.locator('.provider-card').filter({ hasText: 'zen' });
    await expect(zenProvider).toBeVisible({ timeout: 10000 });
    // Expand provider
    const provHeader = zenProvider.locator('.provider-header');
    const chevron = provHeader.locator('.collapse-chevron');
    const isRotated = await chevron.evaluate(el => el.classList.contains('rotated'));
    if (!isRotated) {
      await provHeader.click();
      await expect(chevron).toHaveClass(/rotated/);
    }

    // Type in search
    const searchInput = zenProvider.locator('.model-search-input');
    await searchInput.fill('claude');
    // With search active, all groups auto-expand and only Claude group is visible
    const models = zenProvider.locator('.model-toggle');
    await expect(models.first()).toBeVisible({ timeout: 5000 });
    const count = await models.count();
    expect(count).toBeGreaterThanOrEqual(1);
    // Only one group header should match
    const headers = zenProvider.locator('.model-group-header');
    expect(await headers.count()).toBeLessThanOrEqual(2);

    // Clear search — all groups come back (collapsed)
    await searchInput.fill('');
    const allHeaders = zenProvider.locator('.model-group-header');
    const headerCount = await allHeaders.count();
    expect(headerCount).toBeGreaterThanOrEqual(7);
  });

  baseTest('should show summary footer with enabled/total count', async ({ page }) => {
    const zenProvider = page.locator('.provider-card').filter({ hasText: 'zen' });
    await expect(zenProvider).toBeVisible({ timeout: 10000 });
    // Expand provider
    const provHeader = zenProvider.locator('.provider-header');
    const chevron = provHeader.locator('.collapse-chevron');
    const isRotated = await chevron.evaluate(el => el.classList.contains('rotated'));
    if (!isRotated) {
      await provHeader.click();
      await expect(chevron).toHaveClass(/rotated/);
    }

    // Check summary footer
    const summary = zenProvider.locator('.model-summary');
    await expect(summary).toBeVisible({ timeout: 5000 });
    const text = await summary.textContent();
    expect(text).toMatch(/\d+ \/ \d+ models enabled/);
  });
});

// ─── Studio ───────────────────────────────────────────────────────────
baseTest.describe('Studio', () => {
  baseTest.beforeAll(async () => {
    baseTest.skip(!firstSchemaId, 'No schemas exist — cannot test studio');
  });

  baseTest.beforeEach(async ({ page }) => {
    await setupAuth(page, true);
    await page.goto(`/app/${firstSchemaId}`);
    await expect(page.locator('.studio')).toBeVisible({ timeout: 10000 });
  });

  baseTest('should show studio layout with top bar', async ({ page }) => {
    await expect(page.locator('.studio-topbar')).toBeVisible();
    await expect(page.locator('.app-title')).toBeVisible();
  });

  baseTest('should show Blueprint and Timeline mode tabs', async ({ page }) => {
    await expect(page.locator('.mode-tabs')).toBeVisible();
    await expect(page.locator('.mode-tab', { hasText: 'Blueprint' })).toBeVisible();
    await expect(page.locator('.mode-tab', { hasText: 'Timeline' })).toBeVisible();
  });

  baseTest('should show action buttons in top bar', async ({ page }) => {
    await expect(page.locator('.quickstart-btn')).toBeVisible();
    await expect(page.locator('.generate-prompt-btn')).toBeVisible();
    await expect(page.locator('.run-btn')).toBeVisible();
  });

  baseTest('should show VueFlow canvas', async ({ page }) => {
    await expect(page.locator('.vue-flow')).toBeVisible();
    await expect(page.locator('.vue-flow__pane')).toBeVisible();
  });

  baseTest('should show block palette with draggable items', async ({ page }) => {
    await expect(page.locator('.block-palette')).toBeVisible();
    await expect(page.locator('.palette-label')).toContainText('Blocks');
    // Check for block types
    await expect(page.locator('.palette-item', { hasText: 'Receive' })).toBeVisible();
    await expect(page.locator('.palette-item', { hasText: 'Think' })).toBeVisible();
    await expect(page.locator('.palette-item', { hasText: 'Act' })).toBeVisible();
    await expect(page.locator('.palette-item', { hasText: 'Verify' })).toBeVisible();
    await expect(page.locator('.palette-item', { hasText: 'Review' })).toBeVisible();
    await expect(page.locator('.palette-item', { hasText: 'Remember' })).toBeVisible();
  });

  baseTest('should switch to Timeline mode', async ({ page }) => {
    await page.locator('.mode-tab', { hasText: 'Timeline' }).click();
    await expect(page.locator('.mode-tab.active', { hasText: 'Timeline' })).toBeVisible();
  });

  baseTest('should show Back button that navigates to dashboard', async ({ page }) => {
    await page.locator('.back-btn').click();
    await expect(page).toHaveURL('/');
  });
});

// ─── Persistence ────────────────────────────────────────────────────────
// Tests that schema changes (edges, model, prompt) survive navigation away
// and back. Uses a known schema with Receive + Think nodes (no edges yet).
const PERSISTENCE_SCHEMA_ID = '4c5bd38a-480c-4992-835e-5843fec5d2e8';

baseTest.describe('Persistence', () => {
  baseTest.beforeEach(async ({ page }) => {
    await setupAuth(page, true);
    await page.goto(`/app/${PERSISTENCE_SCHEMA_ID}`);
    await expect(page.locator('.studio')).toBeVisible({ timeout: 10000 });
  });

  baseTest('should persist edge, model and prompt after navigation away and back', async ({ page }) => {
    // Step 1: Create edge from Receive → Think via handle drag
    // VueFlow handles use .source (output) and .target (input) CSS classes
    const sourceHandle = page.locator(
      '.vue-flow__node[data-id="source-1779019232787"] .vue-flow__handle.source'
    );
    const targetHandle = page.locator(
      '.vue-flow__node[data-id="agent-1779021469774"] .vue-flow__handle.target'
    );

    await sourceHandle.dragTo(targetHandle, { force: true });
    // Wait for onConnect + syncFlowToStore API call
    await page.waitForTimeout(2000);

    // Step 2: Click the Think node to open config panel
    const thinkNode = page.locator('.vue-flow__node[data-id="agent-1779021469774"]');
    await thinkNode.click();
    await expect(page.locator('.config-panel')).toBeVisible({ timeout: 5000 });

    // Step 3: Select a model in the config panel
    // Model select: only visible <select class="config-select"> for Agent (Think) nodes
    const modelSelect = page.locator('.config-panel .config-select');
    await modelSelect.selectOption('deepseek-v4-flash-free');
    await page.waitForTimeout(1000); // Wait for saveConfig API call

    // Step 4: Type a test prompt
    // Prompt textarea: <textarea class="config-textarea config-textarea--large">
    const promptInput = page.locator('.config-panel .config-textarea--large');
    await promptInput.fill('Test prompt for persistence');
    await page.waitForTimeout(1000); // Wait for saveConfig API call

    // Step 5: Navigate back to dashboard
    await page.locator('.back-btn').click();
    await expect(page).toHaveURL('/');

    // Step 6: Verify via API that changes were persisted
    const token = realToken;
    const res = await page.request.get(
      `http://localhost:8082/api/schemas/${PERSISTENCE_SCHEMA_ID}`,
      { headers: { Authorization: `Bearer ${token}` } }
    );
    const schema = await res.json();

    // Debug: log the full data object for the Think node
    const thinkNodeRaw = schema.nodes.find((n: any) => n.id === 'agent-1779021469774');
    console.log('Think node data keys:', Object.keys(thinkNodeRaw?.data || {}));
    console.log('Think node data:', JSON.stringify(thinkNodeRaw?.data, null, 2));

    // Check edges
    expect(schema.edges).toBeTruthy();
    expect(schema.edges.length).toBeGreaterThanOrEqual(1);
    const edge = schema.edges.find(
      (e: any) => e.source === 'source-1779019232787' && e.target === 'agent-1779021469774'
    );
    expect(edge).toBeTruthy();

    // Check model and prompt on the Think node
    const thinkNodeData = thinkNodeRaw?.data;
    expect(thinkNodeData).toBeTruthy();
    expect(thinkNodeData.model).toBe('deepseek-v4-flash-free');
    // The backend stores prompt as 'systemPrompt' — check both fields
    const savedPrompt = thinkNodeData.prompt || thinkNodeData.systemPrompt || thinkNodeData.userPrompt;
    expect(savedPrompt).toBe('Test prompt for persistence');

    // Step 7: Navigate back to schema and verify UI shows the changes
    await page.goto(`/app/${PERSISTENCE_SCHEMA_ID}`);
    await expect(page.locator('.studio')).toBeVisible({ timeout: 10000 });

    // Wait for VueFlow to render full schema (nodes + edges)
    await expect(
      page.locator('.vue-flow__edge').first()
    ).toBeVisible({ timeout: 5000 });

    // Click the Think node and verify model + prompt are set in config panel
    await thinkNode.click();
    await expect(page.locator('.config-panel')).toBeVisible({ timeout: 5000 });

    // Verify model is selected
    await expect(modelSelect).toHaveValue('deepseek-v4-flash-free');

    // Verify prompt is visible
    await expect(promptInput).toHaveValue('Test prompt for persistence');
  });
});

// ─── Settings ─────────────────────────────────────────────────────────
baseTest.describe('Settings', () => {
  baseTest.beforeEach(async ({ page }) => {
    await setupAuth(page, true);
    await page.goto('/settings');
  });

  baseTest('should show settings page layout', async ({ page }) => {
    await expect(page.locator('.settings-page')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('.settings-header')).toContainText('Settings');
    await expect(page.locator('.settings-content')).toBeVisible();
  });

  baseTest('should show theme section', async ({ page }) => {
    // Settings loads providers from backend — can be slow under load
    await expect(page.locator('.theme-card')).toBeVisible({ timeout: 35000 });
  });

  baseTest('should navigate back to dashboard via back button', async ({ page }) => {
    await page.locator('.back-btn').click();
    await expect(page).toHaveURL('/');
  });
});

// ─── About ────────────────────────────────────────────────────────────
baseTest.describe('About', () => {
  baseTest('should show about page', async ({ page }) => {
    await page.goto('/about');
    await expect(page.locator('.about')).toBeVisible();
    await expect(page.locator('.about h1')).toContainText('about page');
  });
});
