#!/usr/bin/env bun
/**
 * Axolotl Plugin Bridge — runs in Bun, loads npm OpenCode plugins,
 * communicates with Axolotl via JSON-RPC over stdin/stdout.
 *
 * Protocol: NDJSON (newline-delimited JSON) with JSON-RPC 2.0 framing.
 *
 * Messages from Axolotl → Bun (stdin):
 *   {"jsonrpc":"2.0","id":1,"method":"plugin/initialize","params":{...}}
 *   {"jsonrpc":"2.0","id":2,"method":"tool/execute","params":{"toolId":"ctx_memory","args":{...}}}
 *   {"jsonrpc":"2.0","id":3,"method":"tool/list","params":{}}
 *   {"jsonrpc":"2.0","method":"plugin/shutdown"}  // notification
 *
 * Messages from Bun → Axolotl (stdout):
 *   {"jsonrpc":"2.0","method":"plugin/ready"}  // notification
 *   {"jsonrpc":"2.0","id":1,"result":{"pluginId":"...","version":"..."}}
 *   {"jsonrpc":"2.0","method":"tool/register","params":{"id":"ctx_memory","name":"...","description":"...","inputSchema":{...}}}
 *   {"jsonrpc":"2.0","method":"plugin/log","params":{"level":"info","message":"..."}}
 *   {"jsonrpc":"2.0","id":2,"result":{"success":true,"output":"..."}}
 *   {"jsonrpc":"2.0","id":2,"error":{"code":-1,"message":"Tool execution failed"}}
 */

import { createInterface } from "node:readline";

// ─── Utilities ───

function sendObj(obj) {
  const json = JSON.stringify(obj);
  process.stdout.write(json + "\n");
}

function sendResponse(id, result) {
  sendObj({ jsonrpc: "2.0", id, result });
}

function sendError(id, code, message, data) {
  sendObj({ jsonrpc: "2.0", id, error: { code, message, data } });
}

function sendNotification(method, params) {
  sendObj({ jsonrpc: "2.0", method, params });
}

function log(level, message) {
  sendNotification("plugin/log", { level, message });
}

// ─── State ───

/** Loaded plugin hooks (from Plugin function return value) */
let pluginHooks = null;

/** Registered tool definitions: toolId → { name, description, inputSchema } */
const toolRegistry = new Map();

/** Config passed from Axolotl */
let pluginConfig = null;

/** Project root directory */
let projectRoot = "";

// ─── Plugin Loading ───

/**
 * Load an OpenCode npm plugin and call its Plugin function with a mock context.
 * Returns the plugin's Hooks object.
 */
async function loadPlugin(npmPackage, version, baseConfig) {
  log("info", `Loading plugin: ${npmPackage}@${version || "latest"}`);

  // Resolve the package from node_modules
  const resolved = await resolvePackage(npmPackage);
  const pluginModule = await import(resolved);
  const pluginFn = pluginModule.default || pluginModule.plugin || pluginModule;

  if (typeof pluginFn !== "function") {
    throw new Error(
      `Plugin '${npmPackage}' does not export a default function. ` +
        `Found: ${typeof pluginFn}`
    );
  }

  // Build a minimal PluginContext mock
  const ctx = buildPluginContext(npmPackage, baseConfig);

  // Call the plugin function
  const hooks = await pluginFn(ctx, /* options */ baseConfig);

  log("info", `Plugin '${npmPackage}' loaded successfully`);
  return hooks;
}

/**
 * Resolve a package name to a local path.
 * Tries: ./plugins/node_modules/<pkg> first, then global resolution.
 */
async function resolvePackage(name) {
  const localPath = `${projectRoot}/plugins/node_modules/${name}`;
  try {
    const { stat } = await import("node:fs/promises");
    await stat(localPath);
    return localPath;
  } catch {
    // Fall back to global node_modules resolution
    return name;
  }
}

/**
 * Build a mock PluginContext that satisfies OpenCode's PluginInput interface.
 * Only implements what Magic Context actually uses.
 */
function buildPluginContext(npmPackage, baseConfig) {
  return {
    client: createMockClient(),
    project: {
      name: pluginConfig?.projectName || "axolotl-project",
      directory: projectRoot,
    },
    directory: projectRoot,
    worktree: projectRoot,
    serverUrl: new URL("http://localhost:8082"),
    $: createMockShell(),
  };
}

/**
 * Create a minimal mock of the OpenCode client.
 * Only implements methods that Magic Context actually calls.
 */
function createMockClient() {
  return {
    // Magic Context calls session.list() to get active sessions
    session: {
      list: async () => {
        log("debug", "Mock: session.list() called");
        return [{ id: "axolotl-session-1" }];
      },
      get: async () => {
        log("debug", "Mock: session.get() called");
        return null;
      },
    },
    // Some plugins may try to send messages
    sendMessage: async () => {
      log("debug", "Mock: sendMessage() called");
    },
    // Model/provider lookup
    model: {
      getModel: async () => null,
      listModels: async () => [],
    },
    // Config API for plugin.config() hook
    config: {
      get: async (key) => null,
      set: async (key, value) => {},
    },
    getConfig: async () => ({}),
  };
}

/**
 * Create a mock BunShell ($) that logs commands.
 */
function createMockShell() {
  const shell = (parts, opts) => {
    const cmd = Array.isArray(parts) ? parts.join(" ") : String(parts);
    log("debug", `Mock shell: ${cmd}`);
    return {
      text: () => "",
      json: () => ({}),
      blob: () => new Blob(),
      exitCode: 0,
    };
  };
  shell.which = async (name) => null;
  shell.cwd = () => projectRoot;
  shell.env = {};
  return shell;
}

// ─── Hook Invocation ───

/**
 * Call a plugin hook if it exists.
 */
async function callHook(name, input, output) {
  if (!pluginHooks) return;
  const fn = pluginHooks[name];
  if (typeof fn === "function") {
    try {
      await fn(input, output || {});
    } catch (err) {
      log("error", `Hook '${name}' failed: ${err.message}`);
    }
  }
}

/**
 * Collect tools from plugin after initialization.
 * Checks tool registry (via tool.definition hook) and tool hook.
 */
async function collectPluginTools() {
  const tools = [];

  // Check if plugin registered tools via the `tool` hook
  if (pluginHooks?.tool) {
    for (const [toolId, toolDef] of Object.entries(pluginHooks.tool)) {
      const def = toolDef;
      tools.push({
        id: toolId,
        name: def.name || toolId,
        description: def.description || "",
        inputSchema: def.parameters || {},
        category: "CUSTOM",
      });
    }
  }

  // Also check tools already collected from tool.definition hook
  for (const [toolId, info] of toolRegistry) {
    if (!tools.find((t) => t.id === toolId)) {
      tools.push({
        id: toolId,
        name: info.name || toolId,
        description: info.description || "",
        inputSchema: info.inputSchema || {},
        category: "CUSTOM",
      });
    }
  }

  return tools;
}

// ─── Constants ───

/** Maximum time a plugin tool has to complete before the bridge aborts it. */
const TOOL_EXECUTION_TIMEOUT_MS = 60_000;

// ─── Tool Execution ───

/**
 * Execute a tool call from Axolotl.
 * Magic Context tools use ToolDefinition.execute(args, context) pattern.
 * Wraps execution with a timeout to prevent hung tools from blocking the bridge.
 */
async function executeToolCall(toolId, args) {
  log("info", `Executing plugin tool: ${toolId}`);

  const toolDef = pluginHooks?.tool?.[toolId];
  if (!toolDef) {
    return {
      success: false,
      error: `Tool '${toolId}' not found in plugin registry. Available tools: ${Object.keys(pluginHooks?.tool || {}).join(", ")}`,
    };
  }

  if (typeof toolDef.execute !== "function") {
    return {
      success: false,
      error: `Tool '${toolId}' has no execute method. ToolDef keys: ${Object.keys(toolDef).join(", ")}`,
    };
  }

  try {
    const abortController = new AbortController();

    // Build ToolContext matching @opencode-ai/plugin ToolContext interface
    const context = {
      sessionID: "axolotl-session",
      messageID: crypto.randomUUID(),
      agent: "axolotl-agent",
      directory: projectRoot,
      worktree: pluginConfig?.projectRoot || projectRoot,
      abort: abortController.signal,
      metadata: (input) => {
        log("debug", `Tool metadata: ${JSON.stringify(input)}`);
      },
      ask: async (input) => {
        log("info", `Tool permission request: ${input.permission}`);
        // Auto-grant permissions for headless execution
      },
    };

    // Race: tool execution vs timeout
    // Use .finally() on the tool promise to always clear the timeout
    let timeoutId;
    const result = await Promise.race([
      toolDef.execute(args, context).finally(() => clearTimeout(timeoutId)),
      new Promise((_, reject) => {
        timeoutId = setTimeout(() => {
          abortController.abort();
          reject(new Error("Tool execution timed out after " + TOOL_EXECUTION_TIMEOUT_MS + "ms"));
        }, TOOL_EXECUTION_TIMEOUT_MS);
      }),
    ]);

    return {
      success: true,
      output: typeof result === "string" ? result : JSON.stringify(result),
    };
  } catch (err) {
    log("error", `Tool '${toolId}' execution failed: ${err.message}`);
    return {
      success: false,
      error: `Tool '${toolId}' failed: ${err.message}`,
    };
  }
}

// ─── Message Handler ───

async function handleRequest(id, method, params) {
  try {
    switch (method) {
      case "plugin/initialize":
        await handleInitialize(id, params);
        break;
      case "tool/execute":
        await handleToolExecute(id, params);
        break;
      case "tool/list":
        await handleToolList(id);
        break;
      case "plugin/check_update":
        await handleCheckUpdate(id, params);
        break;
      default:
        sendError(id, -32601, `Method not found: ${method}`);
    }
  } catch (err) {
    sendError(id, -1, err.message, { stack: err.stack });
  }
}

async function handleInitialize(id, params) {
  pluginConfig = params?.config || {};
  projectRoot = params?.projectRoot || process.cwd();
  const npmPackage = params?.plugin;
  const version = params?.version || "latest";

  // Do NOT send plugin/ready here — it's already sent once at startup
  // as the bridge alive signal.

  try {
    // Load the OpenCode plugin
    const hooks = await loadPlugin(npmPackage, version, pluginConfig);
    pluginHooks = hooks;

    // Call plugin's config() hook to let it register agents, tools, commands
    if (typeof hooks.config === "function") {
      const configResult = {};
      await hooks.config(configResult);
      log("info", `Plugin config hook completed`);
    }

    // Call plugin's event hook with "ready" event
    if (typeof hooks.event === "function") {
      await hooks.event({ event: { type: "startup", timestamp: Date.now() } });
    }

    // Collect tools the plugin registered
    const tools = await collectPluginTools();
    log("info", `Plugin registered ${tools.length} tool(s)`);

    // Send tool/register notifications for each tool
    for (const tool of tools) {
      sendNotification("tool/register", tool);
    }

    // Send success response
    sendResponse(id, {
      pluginId: npmPackage,
      version: version,
      toolsRegistered: tools.length,
      tools,
    });

    log("info", `Plugin '${npmPackage}' initialization complete`);
  } catch (err) {
    log("error", `Plugin initialization failed: ${err.message}`);
    sendResponse(id, {
      pluginId: npmPackage || "unknown",
      version: "error",
      error: err.message,
      tools: [],
    });
  }
}

async function handleToolExecute(id, params) {
  const toolId = params?.toolId;
  const args = params?.args || {};

  if (!toolId) {
    sendError(id, -32602, "Missing toolId parameter");
    return;
  }

  const result = await executeToolCall(toolId, args);

  // Call plugin's tool.execute.after hook
  await callHook("tool.execute.after", {
    tool: toolId,
    sessionID: "axolotl",
    callID: crypto.randomUUID(),
    args,
  });

  if (result.success) {
    sendResponse(id, { success: true, output: result.output });
  } else {
    sendResponse(id, { success: false, error: result.error });
  }
}

async function handleToolList(id) {
  const tools = await collectPluginTools();
  sendResponse(id, { tools });
}

async function handleCheckUpdate(id, params) {
  const npmPackage = params?.plugin;
  if (!npmPackage) {
    sendError(id, -32602, "Missing plugin parameter");
    return;
  }

  try {
    const proc = Bun.spawn(["npm", "view", npmPackage, "version"]);
    const latestVersion = (await new Response(proc.stdout).text()).trim();
    const currentVersion = pluginConfig?.version || "unknown";

    sendResponse(id, {
      hasUpdate: latestVersion !== currentVersion,
      currentVersion,
      latestVersion,
    });
  } catch (err) {
    sendResponse(id, {
      hasUpdate: false,
      currentVersion: "unknown",
      latestVersion: "unknown",
      error: err.message,
    });
  }
}

// ─── Startup ───

// Signal that the bridge is alive (single notification, consumed by PluginBridge.waitForReady)
sendNotification("plugin/ready");

log("info", `Axolotl Plugin Bridge started (Bun ${Bun.version})`);

// Read JSON-RPC requests from stdin
const rl = createInterface({
  input: process.stdin,
  output: process.stdout,
  terminal: false,
});

let buffer = "";

rl.on("line", (line) => {
  buffer += line;
  // Handle accumulated lines (in case of split messages)
  try {
    const msg = JSON.parse(buffer);
    buffer = ""; // Reset buffer on successful parse (#20)

    const { id, method, params, error } = msg;

    if (error) {
      log("error", `Received error from Axolotl: ${JSON.stringify(error)}`);
      return;
    }

    if (method && id !== undefined && id !== null) {
      // Request
      handleRequest(id, method, params || {});
    } else if (method) {
      // Notification
      if (method === "plugin/shutdown") {
        log("info", "Shutting down plugin bridge");
        process.exit(0);
      }
    } else {
      log("warn", `Unrecognized message: ${line}`);
    }
  } catch {
    // Incomplete JSON — wait for more data
    // NDJSON ensures complete messages on each line
  }
});

rl.on("close", () => {
  log("info", "stdin closed, shutting down");
  process.exit(0);
});

// Handle shutdown signals
process.on("SIGTERM", () => {
  log("info", "Received SIGTERM");
  process.exit(0);
});

process.on("SIGINT", () => {
  log("info", "Received SIGINT");
  process.exit(0);
});
