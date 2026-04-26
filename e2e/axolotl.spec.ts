import { test, expect } from '@playwright/test';

test.describe('Axolotl E2E', () => {
  const API_URL = process.env.API_URL || 'http://localhost:8080';
  const FRONTEND_URL = process.env.FRONTEND_URL || 'http://localhost:5173';

  test.beforeEach(async ({ page }) => {
    // Increase timeout for LLM calls
    test.setTimeout(60000);
  });

  test('creates and executes simple schema', async ({ page }) => {
    // 1. Open frontend
    await page.goto(FRONTEND_URL);
    await page.waitForLoadState('networkidle');

    // 2. Create new schema
    await page.click('[data-testid="new-schema-btn"]');
    await page.fill('[data-testid="schema-name"]', 'E2E Test Schema');
    await page.click('[data-testid="save-schema-btn"]');

    // 3. Add Source node
    await page.dragAndDrag('[data-testid="node-source"]', { x: 200, y: 200 });
    await page.fill('[data-testid="source-data"]', 'test input');

    // 4. Add Agent node  
    await page.dragAndDrag('[data-testid="node-agent"]', { x: 400, y: 200 });
    await page.click('[data-testid="node-agent"] .node-expand');
    await page.fill('[data-testid="agent-prompt"]', 'Repeat: {{input}}');

    // 5. Add Output node
    await page.dragAndDrag('[data-testid="node-output"]', { x: 600, y: 200 });

    // 6. Connect nodes (drag handle to handle)
    const sourceHandle = await page.locator('[data-testid="node-source"] .handle--source');
    const agentHandle = await page.locator('[data-testid="node-agent"] .handle--target');
    await sourceHandle.dragTo(agentHandle);

    const agentOutputHandle = await page.locator('[data-testid="node-agent"] .handle--source');
    const outputHandle = await page.locator('[data-testid="node-output"] .handle--target');
    await agentOutputHandle.dragTo(outputHandle);

    // 7. Execute schema
    await page.click('[data-testid="execute-btn"]');

    // 8. Wait for execution to complete
    await page.waitForSelector('[data-testid="execution-complete"]', { timeout: 30000 });

    // 9. Verify result
    const result = await page.locator('[data-testid="node-output"] .node-result').textContent();
    expect(result).toContain('test input');
  });

  test('API: create and execute schema', async ({ request }) => {
    // 1. Create schema via API
    const createResponse = await request.post(`${API_URL}/api/schemas`, {
      data: {
        name: 'API E2E Test',
        nodes: [
          { id: 'source', type: 'source', data: { sourceData: 'hello' } },
          { id: 'agent', type: 'agent', data: { userPrompt: 'Say: {{source}}' } },
          { id: 'output', type: 'output' }
        ],
        edges: [
          { source: 'source', target: 'agent' },
          { source: 'agent', target: 'output' }
        ]
      }
    });

    expect(createResponse.ok()).toBe(true);
    const schema = await createResponse.json();
    const schemaId = schema.id;

    // 2. Execute schema
    const execResponse = await request.post(`${API_URL}/api/schemas/${schemaId}/execute`);
    expect(execResponse.ok()).toBe(true);

    // 3. Verify completion (WebSocket will send completion)
    // This is a simplified check - in real test you'd wait for WebSocket
    const getResponse = await request.get(`${API_URL}/api/schemas/${schemaId}`);
    expect(getResponse.ok()).toBe(true);

    // 4. Cleanup
    await request.delete(`${API_URL}/api/schemas/${schemaId}`);
  });

  test('API: health check', async ({ request }) => {
    const response = await request.get(`${API_URL}/api/health`);
    expect(response.ok()).toBe(true);
    
    const data = await response.json();
    expect(data.status).toBe('UP');
  });
});