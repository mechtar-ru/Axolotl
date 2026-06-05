package com.agent.orchestrator.mcp;

import com.agent.orchestrator.service.PlanService;
import com.agent.orchestrator.service.PlanStepService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP (Model Context Protocol) server for project plan management.
 * Handles JSON-RPC 2.0 requests at POST /mcp endpoint.
 * Also responds to GET /mcp with health check.
 * Runs on the main Spring Boot port (8080).
 */
@RestController
@RequestMapping("/mcp")
public class PlanMcpServer {

    private static final Logger log = LoggerFactory.getLogger(PlanMcpServer.class);

    private final PlanService planService;
    private final PlanTools planTools;
    private final PlanStepTools planStepTools;

    public PlanMcpServer(PlanService planService, PlanStepService planStepService) {
        this.planService = planService;
        this.planTools = new PlanTools(planService);
        this.planStepTools = new PlanStepTools(planStepService);
        log.info("✅ MCP сервер инициализирован. Доступен на /mcp");
    }

    // === GET — health check ===

    @GetMapping
    public ResponseEntity<Map<String, Object>> getHealth() {
        List<String> toolNames = new ArrayList<>();
        planTools.getToolSpecs().stream().map(PlanTools.ToolSpec::name).forEach(toolNames::add);
        planStepTools.getToolSpecs().stream().map(PlanTools.ToolSpec::name).forEach(toolNames::add);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "MCP Server running");
        response.put("protocolVersion", "2024-11-05");
        response.put("tools", toolNames);
        response.put("endpoint", "POST /mcp");
        return ResponseEntity.ok(response);
    }

    // === POST — JSON-RPC 2.0 ===

    @PostMapping
    public ResponseEntity<Map<String, Object>> handleRequest(@RequestBody Map<String, Object> request) {
        String method = (String) request.get("method");
        Object id = request.get("id");

        log.info("MCP Request received: method={}, id={}", method, id);

        if (method == null) {
            log.warn("MCP Request missing method");
            return jsonRpcError(id, -32600, "Invalid Request: missing method", null);
        }

        try {
            Map<String, Object> result = switch (method) {
                case "initialize" -> handleInitialize();
                case "notifications/initialized" -> handleNotificationInitialized();
                case "ping" -> handlePing();
                case "tools/list" -> handleToolsList();
                case "tools/call" -> handleToolCall((Map<String, Object>) request.get("params"));
                default -> throw new McpException(-32601, "Method not found: " + method);
            };

            // Notifications (no id) — no JSON-RPC response per spec
            if (id == null && "notifications/initialized".equals(method)) {
                return ResponseEntity.ok().build();
            }

            return jsonRpcSuccess(id, result);
        } catch (McpException e) {
            log.warn("MCP error: code={}, message={}", e.getCode(), e.getMessage());
            return jsonRpcError(id, e.getCode(), e.getMessage(), e.getData());
        } catch (Exception e) {
            log.error("MCP internal error: {}", e.getMessage(), e);
            return jsonRpcError(id, -32603, "Internal error: " + e.getMessage(), null);
        }
    }

    private Map<String, Object> handleInitialize() {
        log.debug("MCP initialize");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", "2024-11-05");
        result.put("serverInfo", Map.of(
                "name", "axolotl-plan-server",
                "version", "1.0.0"
        ));
        result.put("capabilities", Map.of(
                "tools", new LinkedHashMap<String, Object>()
        ));
        return result;
    }

    private Map<String, Object> handleNotificationInitialized() {
        log.debug("MCP notifications/initialized — client ready");
        return Map.of("status", "initialized");
    }

    private Map<String, Object> handlePing() {
        log.debug("MCP ping");
        return Map.of("status", "pong");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleToolsList() {
        Map<String, Object> response = new LinkedHashMap<>();
        List<Map<String, Object>> tools = new ArrayList<>();

        // Old plan tools
        for (PlanTools.ToolSpec spec : planTools.getToolSpecs()) {
            tools.add(buildToolSpec(spec));
        }

        // New plan-step tools
        for (PlanTools.ToolSpec spec : planStepTools.getToolSpecs()) {
            tools.add(buildToolSpec(spec));
        }

        response.put("tools", tools);
        return response;
    }

    private Map<String, Object> buildToolSpec(PlanTools.ToolSpec spec) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", spec.name());
        tool.put("description", spec.description());

        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", spec.properties());

        List<String> required = new ArrayList<>();
        Map<String, Object> cleanProperties = new LinkedHashMap<>();
        spec.properties().forEach((key, value) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> prop = new LinkedHashMap<>((Map<String, Object>) value);
            if (Boolean.TRUE.equals(prop.remove("required"))) {
                required.add(key);
            }
            cleanProperties.put(key, prop);
        });
        if (!required.isEmpty()) {
            inputSchema.put("required", required);
        }
        inputSchema.put("properties", cleanProperties);

        tool.put("inputSchema", inputSchema);
        return tool;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleToolCall(Map<String, Object> params) {
        if (params == null) {
            throw new McpException(-32602, "Missing params for tool call");
        }

        String toolName = (String) params.get("name");
        Map<String, Object> args = (Map<String, Object>) params.getOrDefault("arguments", Map.of());

        log.info("MCP tool call: name={}", toolName);

        // Route to the appropriate tool handler
        Set<String> stepToolNames = Set.of("read_plan_steps", "add_plan_steps",
                "update_plan_step_status", "get_ready_steps", "get_plan_graph");
        String result = stepToolNames.contains(toolName)
                ? planStepTools.callTool(toolName, args)
                : planTools.callTool(toolName, args);

        Map<String, Object> response = new LinkedHashMap<>();
        List<Map<String, Object>> content = new ArrayList<>();
        Map<String, Object> textContent = new LinkedHashMap<>();
        textContent.put("type", "text");
        textContent.put("text", result);
        content.add(textContent);
        response.put("content", content);
        return response;
    }

    private ResponseEntity<Map<String, Object>> jsonRpcSuccess(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<Map<String, Object>> jsonRpcError(Object id, int code, String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        if (data != null) error.put("data", data);
        response.put("error", error);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    private static class McpException extends RuntimeException {
        private final int code;
        private final Object data;

        McpException(int code, String message) {
            super(message);
            this.code = code;
            this.data = null;
        }

        McpException(int code, String message, Object data) {
            super(message);
            this.code = code;
            this.data = data;
        }

        int getCode() { return code; }
        Object getData() { return data; }
    }
}
