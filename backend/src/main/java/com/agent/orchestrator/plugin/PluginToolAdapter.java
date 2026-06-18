package com.agent.orchestrator.plugin;

import com.agent.orchestrator.model.Tool;
import com.agent.orchestrator.model.Tool.ToolResult;
import com.agent.orchestrator.service.ToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adapts tools registered by plugins into Axolotl's ToolExecutor.
 * <p>
 * When a plugin sends "tool/register" or returns tools in its initialization response,
 * this adapter creates matching Tool definitions and proxy handlers that forward
 * executions to the plugin's Bun process via PluginBridge.
 */
public class PluginToolAdapter {

    private static final Logger log = LoggerFactory.getLogger(PluginToolAdapter.class);

    private final PluginBridge bridge;
    private final ToolExecutor toolExecutor;
    private final Map<String, Tool> pluginTools = new ConcurrentHashMap<>();
    private final Map<String, String> prefixedToRawId = new ConcurrentHashMap<>();

    public PluginToolAdapter(PluginBridge bridge, ToolExecutor toolExecutor) {
        this.bridge = bridge;
        this.toolExecutor = toolExecutor;
    }

    /**
     * Register tools from the plugin's initialization response.
     * Called after bridge.start() completes.
     * Tools are already registered via "tool/register" notifications from the bridge.
     * This method is kept for future use when the init response carries inline tools.
     */
    public void registerFromInit(JsonNode initParams) {
        if (initParams == null) return;
        JsonNode tools = initParams.path("tools");
        if (tools != null && tools.isArray()) {
            for (JsonNode toolNode : tools) {
                registerSingleTool(toolNode);
            }
        }
    }

    /**
     * Register a single tool from a plugin notification.
     * Expected format (JSON-RPC params):
     * <pre>
     * {
     *   "id": "ctx_memory",
     *   "name": "Write Memory",
     *   "description": "...",
     *   "inputSchema": { ... },
     *   "category": "MEMORY"
     * }
     * </pre>
     */
    public void registerSingleTool(JsonNode toolDef) {
        try {
            String rawId = toolDef.path("id").asText();
            if (rawId == null || rawId.isBlank()) {
                log.warn("[{}] Plugin tool missing 'id', skipping", bridge.getName());
                return;
            }

            // Prefix tool ID with plugin namespace to prevent cross-plugin collisions
            String id = bridge.getName() + "/" + rawId;

            String name = toolDef.path("name").asText(rawId);
            String description = toolDef.path("description").asText("");
            String inputSchema = toolDef.path("inputSchema").toString();
            String categoryStr = toolDef.path("category").asText("CUSTOM");

            Tool.ToolCategory category;
            try {
                category = Tool.ToolCategory.valueOf(categoryStr);
            } catch (IllegalArgumentException e) {
                category = Tool.ToolCategory.CUSTOM;
            }

            Tool tool = new Tool(id, name, description, inputSchema, category);
            pluginTools.put(id, tool);
            prefixedToRawId.put(id, rawId); // raw ID for bridge communication

            // Register tool definition in ToolExecutor
            toolExecutor.registerTool(tool);

            // Register plugin proxy handler with toolId captured in closure
            toolExecutor.registerPluginHandler(id, (params, permission) ->
                    executePluginTool(id, params));

            log.info("[{}] Registered plugin tool: {} ({})", bridge.getName(), id, name);
        } catch (Exception e) {
            log.error("[{}] Failed to register plugin tool: {}", bridge.getName(), e.getMessage(), e);
        }
    }

    /**
     * Execute a plugin tool by forwarding to the Bun process.
     * Called from ToolExecutor when a plugin tool handler is invoked.
     */
    public ToolResult executePluginTool(String toolId, Map<String, Object> args) {
        try {
            // Use raw tool ID for bridge communication (strip prefix)
            String bridgeToolId = prefixedToRawId.getOrDefault(toolId, toolId);

            // Build execute params: { "toolId": "...", "args": {...} }
            Map<String, Object> executeParams = new LinkedHashMap<>();
            executeParams.put("toolId", bridgeToolId);
            executeParams.put("args", args != null ? args : Map.of());

            long start = System.currentTimeMillis();
            PluginMessage.ParsedMessage response = bridge.sendRequest("tool/execute", executeParams);
            long duration = System.currentTimeMillis() - start;

            if (response == null || response.isError()) {
                return ToolResult.error(response != null ? response.error() : "No response from plugin");
            }

            // Use result() for responses (stores JSON "result" field)
            JsonNode result = response.result();
            boolean success = result != null && result.path("success").asBoolean(false);
            String output = result != null ? result.path("output").asText("") : "";
            String error = result != null ? result.path("error").asText(null) : null;

            ToolResult toolResult = success ? ToolResult.ok(output) : ToolResult.error(error != null ? error : output);
            toolResult.setExecutionTimeMs(duration);
            return toolResult;

        } catch (Exception e) {
            return ToolResult.error("Plugin tool execution failed: " + e.getMessage());
        }
    }

    public Map<String, Tool> getPluginTools() {
        return Collections.unmodifiableMap(pluginTools);
    }

    public int getToolCount() {
        return pluginTools.size();
    }

    /** Listen for tool/execute requests FROM the plugin (plugin-initiated tool calls not supported yet) */
    public void handleIncomingToolRequest(PluginMessage.ParsedMessage msg) {
        log.warn("[{}] Incoming tool requests not yet supported", bridge.getName());
    }
}
