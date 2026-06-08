package com.agent.orchestrator.plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/**
 * JSON-RPC 2.0 message types for Axolotl ↔ Bun plugin bridge protocol.
 * <p>
 * Protocol: newline-delimited JSON (NDJSON) over stdin/stdout.
 * Each message is one JSON-RPC 2.0 object terminated by '\n'.
 */
public class PluginMessage {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PluginMessage() {}

    // ─── Request / Response / Notification ───

    /** Build a JSON-RPC request (expects response) */
    public static String buildRequest(String method, Map<String, Object> params, int id) {
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("jsonrpc", "2.0");
            node.put("id", id);
            node.put("method", method);
            if (params != null && !params.isEmpty()) {
                node.set("params", MAPPER.valueToTree(params));
            }
            return MAPPER.writeValueAsString(node) + "\n";
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to build request: " + e.getMessage(), e);
        }
    }

    /** Build a JSON-RPC response */
    public static String buildResponse(int id, Object result) {
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("jsonrpc", "2.0");
            node.put("id", id);
            node.set("result", MAPPER.valueToTree(result));
            return MAPPER.writeValueAsString(node) + "\n";
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to build response: " + e.getMessage(), e);
        }
    }

    /** Build a JSON-RPC error response */
    public static String buildError(int id, int code, String message, Object data) {
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("jsonrpc", "2.0");
            node.put("id", id);
            ObjectNode error = node.putObject("error");
            error.put("code", code);
            error.put("message", message);
            if (data != null) {
                error.set("data", MAPPER.valueToTree(data));
            }
            return MAPPER.writeValueAsString(node) + "\n";
        } catch (JsonProcessingException e) {
            // Last resort: plain-text error that's still parseable
            return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32700,\"message\":\"Serialize failed\"}}\n";
        }
    }

    /** Build a JSON-RPC notification (no response expected) */
    public static String buildNotification(String method, Map<String, Object> params) {
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("jsonrpc", "2.0");
            node.put("method", method);
            if (params != null && !params.isEmpty()) {
                node.set("params", MAPPER.valueToTree(params));
            }
            return MAPPER.writeValueAsString(node) + "\n";
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    // ─── Parse incoming line from Bun ───

    /**
     * Parse a single JSON-RPC message from Bun's stdout.
     * Returns a parsed message struct.
     */
    public static ParsedMessage parse(String line) {
        try {
            JsonNode root = MAPPER.readTree(line);
            String version = root.path("jsonrpc").asText();
            if (!"2.0".equals(version)) {
                return ParsedMessage.error("Invalid jsonrpc version: " + version);
            }

            boolean hasId = root.has("id") && !root.path("id").isNull();
            int id = hasId ? root.path("id").asInt(-1) : -1;
            String method = root.has("method") ? root.path("method").asText() : null;
            JsonNode params = root.path("params");
            JsonNode result = root.path("result");
            JsonNode errorNode = root.path("error");

            String errorStr = null;
            if (!errorNode.isMissingNode() && !errorNode.isNull()) {
                errorStr = errorNode.path("message").asText("Unknown plugin error");
            }

            if (method != null && hasId) {
                // request
                return new ParsedMessage(Type.REQUEST, id, method, params, null);
            } else if (method != null) {
                // notification
                return new ParsedMessage(Type.NOTIFICATION, null, method, params, null);
            } else if (hasId) {
                // response — store result in the same field as params for simplicity
                return new ParsedMessage(Type.RESPONSE, id, null, result != null ? result : params, errorStr);
            } else {
                return ParsedMessage.error("Unrecognized message format");
            }
        } catch (Exception e) {
            return ParsedMessage.error("Parse error: " + e.getMessage());
        }
    }

    // ─── Types ───

    public enum Type { REQUEST, RESPONSE, NOTIFICATION }

    /**
     * Parsed JSON-RPC message.
     * <p>
     * NOTE: The {@code params} field is used polymorphically:
     * <ul>
     *   <li>For requests/notifications — holds the "params" JSON object</li>
     *   <li>For responses — holds the "result" JSON object (the raw JSON field is called
     *       "result" in the wire protocol, but stored in the same slot)</li>
     * </ul>
     * Use {@link #result()} for responses to make intent clear.
     */
    public record ParsedMessage(Type type, Integer id, String method, JsonNode params, String error) {

        /** Factory for an error-state message (parse failure) */
        public static ParsedMessage error(String errorMsg) {
            return new ParsedMessage(null, null, null, null, errorMsg);
        }

        public boolean isError() { return error != null; }

        /** True if this is a parse error or otherwise invalid message */
        public boolean isInvalid() { return type == null; }

        // ─── Type checks ───

        public boolean isRequest() { return type == Type.REQUEST; }
        public boolean isResponse() { return type == Type.RESPONSE; }
        public boolean isNotification() { return type == Type.NOTIFICATION; }

        /**
         * For responses: return the result payload (synonym for params()).
         * <p>
         * JSON-RPC puts results in the "result" field, but this record stores it
         * in the same slot as params. Use this method for clarity when handling responses.
         */
        public JsonNode result() {
            return params;
        }
    }

    // ─── Convenience methods for tool execution ───

    /** Build a tool/execute request */
    public static String buildToolExecuteRequest(int id, String toolId, Map<String, Object> args) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("toolId", toolId);
        params.put("args", args);
        return buildRequest("tool/execute", params, id);
    }

    /** Build a tool/list request */
    public static String buildToolListRequest(int id) {
        return buildRequest("tool/list", Map.of(), id);
    }

    /** Build an initialize request */
    public static String buildInitializeRequest(int id, Map<String, Object> config) {
        return buildRequest("plugin/initialize", config, id);
    }

    /** Build a log notification (Bun → Axolotl) */
    public static String buildLogNotification(String level, String message) {
        return buildNotification("plugin/log", Map.of("level", level, "message", message));
    }

    /** Check if a line is a complete JSON-RPC message (starts with '{') */
    public static boolean looksLikeJson(String line) {
        return line != null && line.trim().startsWith("{");
    }

    /** Extract tool ID from a tool/execute request params */
    public static String extractToolId(JsonNode params) {
        return params != null ? params.path("toolId").asText() : null;
    }

    /** Extract args map from a tool/execute request params */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> extractToolArgs(JsonNode params) {
        if (params == null) return Map.of();
        JsonNode args = params.path("args");
        if (args == null || args.isNull() || args.isMissingNode()) return Map.of();
        try {
            return MAPPER.convertValue(args, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
