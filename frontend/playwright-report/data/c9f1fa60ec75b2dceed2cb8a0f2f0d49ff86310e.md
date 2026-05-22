# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: app.spec.ts >> About >> should show about page
- Location: e2e/app.spec.ts:474:3

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: locator('.about')
Expected: visible
Timeout: 5000ms
Error: element(s) not found

Call log:
  - Expect "toBeVisible" with timeout 5000ms
  - waiting for locator('.about')

```

# Test source

```ts
  376 | 
  377 |     // Step 2: Click the Think node to open config panel
  378 |     const thinkNode = page.locator('.vue-flow__node[data-id="agent-1779021469774"]');
  379 |     await thinkNode.click();
  380 |     await expect(page.locator('.config-panel')).toBeVisible({ timeout: 5000 });
  381 | 
  382 |     // Step 3: Select a model in the config panel
  383 |     // Model select: only visible <select class="config-select"> for Agent (Think) nodes
  384 |     const modelSelect = page.locator('.config-panel .config-select');
  385 |     await modelSelect.selectOption('deepseek-v4-flash-free');
  386 |     await page.waitForTimeout(1000); // Wait for saveConfig API call
  387 | 
  388 |     // Step 4: Type a test prompt
  389 |     // Prompt textarea: <textarea class="config-textarea config-textarea--large">
  390 |     const promptInput = page.locator('.config-panel .config-textarea--large');
  391 |     await promptInput.fill('Test prompt for persistence');
  392 |     await page.waitForTimeout(1000); // Wait for saveConfig API call
  393 | 
  394 |     // Step 5: Navigate back to dashboard
  395 |     await page.locator('.back-btn').click();
  396 |     await expect(page).toHaveURL('/');
  397 | 
  398 |     // Step 6: Verify via API that changes were persisted
  399 |     const token = realToken;
  400 |     const res = await page.request.get(
  401 |       `http://localhost:8082/api/schemas/${PERSISTENCE_SCHEMA_ID}`,
  402 |       { headers: { Authorization: `Bearer ${token}` } }
  403 |     );
  404 |     const schema = await res.json();
  405 | 
  406 |     // Debug: log the full data object for the Think node
  407 |     const thinkNodeRaw = schema.nodes.find((n: any) => n.id === 'agent-1779021469774');
  408 |     console.log('Think node data keys:', Object.keys(thinkNodeRaw?.data || {}));
  409 |     console.log('Think node data:', JSON.stringify(thinkNodeRaw?.data, null, 2));
  410 | 
  411 |     // Check edges
  412 |     expect(schema.edges).toBeTruthy();
  413 |     expect(schema.edges.length).toBeGreaterThanOrEqual(1);
  414 |     const edge = schema.edges.find(
  415 |       (e: any) => e.source === 'source-1779019232787' && e.target === 'agent-1779021469774'
  416 |     );
  417 |     expect(edge).toBeTruthy();
  418 | 
  419 |     // Check model and prompt on the Think node
  420 |     const thinkNodeData = thinkNodeRaw?.data;
  421 |     expect(thinkNodeData).toBeTruthy();
  422 |     expect(thinkNodeData.model).toBe('deepseek-v4-flash-free');
  423 |     // The backend stores prompt as 'systemPrompt' — check both fields
  424 |     const savedPrompt = thinkNodeData.prompt || thinkNodeData.systemPrompt || thinkNodeData.userPrompt;
  425 |     expect(savedPrompt).toBe('Test prompt for persistence');
  426 | 
  427 |     // Step 7: Navigate back to schema and verify UI shows the changes
  428 |     await page.goto(`/app/${PERSISTENCE_SCHEMA_ID}`);
  429 |     await expect(page.locator('.studio')).toBeVisible({ timeout: 10000 });
  430 | 
  431 |     // Wait for VueFlow to render full schema (nodes + edges)
  432 |     await expect(
  433 |       page.locator('.vue-flow__edge').first()
  434 |     ).toBeVisible({ timeout: 5000 });
  435 | 
  436 |     // Click the Think node and verify model + prompt are set in config panel
  437 |     await thinkNode.click();
  438 |     await expect(page.locator('.config-panel')).toBeVisible({ timeout: 5000 });
  439 | 
  440 |     // Verify model is selected
  441 |     await expect(modelSelect).toHaveValue('deepseek-v4-flash-free');
  442 | 
  443 |     // Verify prompt is visible
  444 |     await expect(promptInput).toHaveValue('Test prompt for persistence');
  445 |   });
  446 | });
  447 | 
  448 | // ─── Settings ─────────────────────────────────────────────────────────
  449 | baseTest.describe('Settings', () => {
  450 |   baseTest.beforeEach(async ({ page }) => {
  451 |     await setupAuth(page, true);
  452 |     await page.goto('/settings');
  453 |   });
  454 | 
  455 |   baseTest('should show settings page layout', async ({ page }) => {
  456 |     await expect(page.locator('.settings-page')).toBeVisible({ timeout: 10000 });
  457 |     await expect(page.locator('.settings-header')).toContainText('Settings');
  458 |     await expect(page.locator('.settings-content')).toBeVisible();
  459 |   });
  460 | 
  461 |   baseTest('should show theme section', async ({ page }) => {
  462 |     // Settings loads providers from backend — can be slow under load
  463 |     await expect(page.locator('.theme-card')).toBeVisible({ timeout: 35000 });
  464 |   });
  465 | 
  466 |   baseTest('should navigate back to dashboard via back button', async ({ page }) => {
  467 |     await page.locator('.back-btn').click();
  468 |     await expect(page).toHaveURL('/');
  469 |   });
  470 | });
  471 | 
  472 | // ─── About ────────────────────────────────────────────────────────────
  473 | baseTest.describe('About', () => {
  474 |   baseTest('should show about page', async ({ page }) => {
  475 |     await page.goto('/about');
> 476 |     await expect(page.locator('.about')).toBeVisible();
      |                                          ^ Error: expect(locator).toBeVisible() failed
  477 |     await expect(page.locator('.about h1')).toContainText('about page');
  478 |   });
  479 | });
  480 | 
```