#!/usr/bin/env node
/**
 * Playwright MCP Server - Node.js version
 */

const { chromium } = require('playwright');

let browser = null;
let context = null;
let page = null;

async function start() {
  browser = await chromium.launch({ headless: true });
  context = await browser.newContext();
  page = await context.newPage();
  console.error("Playwright MCP started", process.stderr.fd);
}

const tools = {
  navigate: async (args) => {
    await page.goto(args.url);
    return `Navigated to ${args.url}`;
  },
  click: async (args) => {
    await page.click(args.selector);
    return `Clicked ${args.selector}`;
  },
  type: async (args) => {
    await page.fill(args.selector, args.text);
    return `Typed into ${args.selector}`;
  },
  screenshot: async (args) => {
    const path = args.path || '/tmp/screenshot.png';
    await page.screenshot({ path });
    return `Screenshot saved to ${path}`;
  },
  evaluate: async (args) => {
    return await page.evaluate(args.script);
  },
  get_text: async (args) => {
    return await page.textContent(args.selector);
  },
  go_back: async () => {
    await page.goBack();
    return 'Went back';
  },
  go_forward: async () => {
    await page.goForward();
    return 'Went forward';
  }
};

async function handleTool(toolName, args) {
  const tool = tools[toolName];
  if (!tool) {
    return { ok: false, data: `Unknown tool: ${toolName}` };
  }
  try {
    const result = await tool(args);
    return { ok: true, data: result };
  } catch (e) {
    return { ok: false, data: e.message };
  }
}

async function main() {
  await start();
  
  const readline = require('readline');
  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
    terminal: false
  });
  
  rl.on('line', async (line) => {
    if (!line.trim()) return;
    try {
      const request = JSON.parse(line);
      const result = await handleTool(request.tool, request.args || {});
      console.log(JSON.stringify(result));
    } catch (e) {
      console.log(JSON.stringify({ ok: false, data: e.message }));
    }
  });
}

main().catch(e => {
  console.error(e);
  process.exit(1);
});