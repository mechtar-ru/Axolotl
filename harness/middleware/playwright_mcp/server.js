#!/usr/bin/env node
/**
 * Playwright MCP Server — Node.js, stdio JSON-RPC 2.0 transport.
 */

const { chromium } = require('playwright');

let browser = null;
let context = null;
let page = null;

async function start() {
  browser = await chromium.launch({ headless: true });
  context = await browser.newContext({
    viewport: { width: 1280, height: 720 },
    userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Axolotl-Harness/1.0',
  });
  page = await context.newPage();
  process.stderr.write('Playwright MCP server started (stdio)\n');
}

function textResult(text) {
  return { content: [{ type: 'text', text }] };
}

function errorResult(msg) {
  return { content: [{ type: 'text', text: `Error: ${msg}` }], isError: true };
}

const tools = {
  navigate: async (args) => {
    await page.goto(args.url, { waitUntil: 'domcontentloaded' });
    return textResult(`Navigated to ${args.url}`);
  },
  click: async (args) => {
    await page.click(args.selector);
    return textResult(`Clicked ${args.selector}`);
  },
  fill: async (args) => {
    await page.fill(args.selector, args.text);
    return textResult(`Filled ${args.selector}`);
  },
  screenshot: async (args) => {
    await page.waitForLoadState('networkidle');
    const buffer = await page.screenshot({ fullPage: args.fullPage || false });
    const b64 = buffer.toString('base64');
    return {
      content: [
        { type: 'text', text: `Screenshot (${buffer.length} bytes)` },
        { type: 'image', data: b64, mimeType: 'image/png' },
      ],
    };
  },
  get_text: async (args) => {
    const parts = await page.locator(args.selector).allTextContents();
    return textResult(parts.join('\n'));
  },
  evaluate: async (args) => {
    const result = await page.evaluate(args.script);
    return textResult(JSON.stringify(result, null, 2));
  },
  get_html: async (args) => {
    const html = await page.innerHTML(args.selector || 'html');
    return textResult(html);
  },
  go_back: async () => {
    await page.goBack();
    return textResult('Went back');
  },
  go_forward: async () => {
    await page.goForward();
    return textResult('Went forward');
  },
};

const toolDefinitions = [
  { name: 'navigate', description: 'Go to a URL', inputSchema: { type: 'object', properties: { url: { type: 'string' } }, required: ['url'] } },
  { name: 'click', description: 'Click an element by CSS selector', inputSchema: { type: 'object', properties: { selector: { type: 'string' } }, required: ['selector'] } },
  { name: 'fill', description: 'Type text into a form field', inputSchema: { type: 'object', properties: { selector: { type: 'string' }, text: { type: 'string' } }, required: ['selector', 'text'] } },
  { name: 'screenshot', description: 'Capture page screenshot', inputSchema: { type: 'object', properties: { fullPage: { type: 'boolean' } } } },
  { name: 'get_text', description: 'Get text content of element(s)', inputSchema: { type: 'object', properties: { selector: { type: 'string' } }, required: ['selector'] } },
  { name: 'evaluate', description: 'Run JavaScript in page context', inputSchema: { type: 'object', properties: { script: { type: 'string' } }, required: ['script'] } },
  { name: 'get_html', description: 'Get inner HTML of element', inputSchema: { type: 'object', properties: { selector: { type: 'string' } } } },
  { name: 'go_back', description: 'Go back in browser history', inputSchema: { type: 'object', properties: {} } },
  { name: 'go_forward', description: 'Go forward in browser history', inputSchema: { type: 'object', properties: {} } },
];

async function handleTool(name, args) {
  const tool = tools[name];
  if (!tool) return errorResult(`Unknown tool: ${name}`);
  try {
    return await tool(args);
  } catch (e) {
    return errorResult(e.message);
  }
}

async function main() {
  await start();

  const readline = require('readline');
  const rl = readline.createInterface({ input: process.stdin, terminal: false });

  rl.on('line', async (line) => {
    line = line.trim();
    if (!line) return;

    let request;
    try {
      request = JSON.parse(line);
    } catch {
      return;
    }

    const { id, method, params } = request;
    let response;

    if (method === 'tools/list') {
      response = { jsonrpc: '2.0', id, result: { tools: toolDefinitions } };
    } else if (method === 'tools/call') {
      const result = await handleTool(params.name, params.arguments || {});
      response = { jsonrpc: '2.0', id, result };
    } else if (method === 'initialize') {
      response = { jsonrpc: '2.0', id, result: { protocolVersion: '0.1.0', capabilities: { tools: {} }, serverInfo: { name: 'playwright-mcp', version: '1.0.0' } } };
    } else {
      response = { jsonrpc: '2.0', id, error: { code: -32601, message: `Method not found: ${method}` } };
    }

    console.log(JSON.stringify(response));
  });
}

main().catch(e => {
  process.stderr.write(e.stack + '\n');
  process.exit(1);
});
