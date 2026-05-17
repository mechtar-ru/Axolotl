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
    await expect(page.locator('.btn-secondary')).toContainText('Cancel');
    await expect(page.locator('.app-modal-content .btn-primary')).toContainText('Create');
    // Close modal
    await page.locator('.btn-secondary').click();
    await expect(page.locator('.app-modal-content')).not.toBeVisible();
  });

  baseTest('should navigate to settings via nav link', async ({ page }) => {
    await page.locator('.nav-link', { hasText: 'Settings' }).click();
    await expect(page).toHaveURL(/\/settings/);
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
