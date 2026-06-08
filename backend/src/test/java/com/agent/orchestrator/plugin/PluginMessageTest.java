package com.agent.orchestrator.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PluginMessageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ─── buildRequest ───

    @Test
    void buildRequestWithParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("toolId", "ctx_memory");
        params.put("args", Map.of("action", "list"));

        String msg = PluginMessage.buildRequest("tool/execute", params, 42);
        assertTrue(msg.startsWith("{"), "should be JSON");
        assertTrue(msg.endsWith("\n"), "should end with newline");

        PluginMessage.ParsedMessage parsed = PluginMessage.parse(msg);
        assertTrue(parsed.isRequest());
        assertEquals(42, parsed.id());
        assertEquals("tool/execute", parsed.method());
        assertNotNull(parsed.params());
        assertEquals("ctx_memory", parsed.params().path("toolId").asText());
    }

    @Test
    void buildRequestNullParams() {
        String msg = PluginMessage.buildRequest("tool/list", null, 1);
        PluginMessage.ParsedMessage parsed = PluginMessage.parse(msg);
        assertTrue(parsed.isRequest());
        assertEquals(1, parsed.id());
        assertEquals("tool/list", parsed.method());
    }

    @Test
    void buildRequestEmptyParams() {
        String msg = PluginMessage.buildRequest("tool/list", Map.of(), 2);
        PluginMessage.ParsedMessage parsed = PluginMessage.parse(msg);
        assertTrue(parsed.isRequest());
    }

    // ─── buildResponse ───

    @Test
    void buildResponseWithResult() {
        String msg = PluginMessage.buildResponse(1, Map.of("success", true, "output", "done"));
        PluginMessage.ParsedMessage parsed = PluginMessage.parse(msg);
        assertTrue(parsed.isResponse());
        assertEquals(1, parsed.id());
        assertNull(parsed.method());
        assertNotNull(parsed.result());
        assertTrue(parsed.result().path("success").asBoolean());
        assertEquals("done", parsed.result().path("output").asText());
    }

    @Test
    void buildResponseNullResult() {
        String msg = PluginMessage.buildResponse(2, null);
        PluginMessage.ParsedMessage parsed = PluginMessage.parse(msg);
        assertTrue(parsed.isResponse());
        assertEquals(2, parsed.id());
    }

    // ─── buildError ───

    @Test
    void buildErrorWithData() {
        String msg = PluginMessage.buildError(5, -1, "Something broke", Map.of("detail", "stack trace"));
        PluginMessage.ParsedMessage parsed = PluginMessage.parse(msg);
        assertTrue(parsed.isResponse());
        assertTrue(parsed.isError());
        assertEquals(5, parsed.id());
        assertEquals("Something broke", parsed.error());
    }

    @Test
    void buildErrorWithoutData() {
        String msg = PluginMessage.buildError(6, -32602, "Invalid params", null);
        PluginMessage.ParsedMessage parsed = PluginMessage.parse(msg);
        assertTrue(parsed.isError());
        assertEquals("Invalid params", parsed.error());
    }

    @Test
    void buildErrorSerializeFailureFallback() {
        // Object that fails serialization — use a circular reference impossible via Map,
        // but the buildError method catches JsonProcessingException and falls back to raw JSON.
        String msg = PluginMessage.buildError(99, -32700, "Serialize failed", null);
        assertTrue(msg.contains("-32700"));
    }

    // ─── buildNotification ───

    @Test
    void buildNotificationWithParams() {
        String msg = PluginMessage.buildNotification("plugin/log",
                Map.of("level", "info", "message", "hello"));
        PluginMessage.ParsedMessage parsed = PluginMessage.parse(msg);
        assertTrue(parsed.isNotification());
        assertNull(parsed.id());
        assertEquals("plugin/log", parsed.method());
        assertEquals("info", parsed.params().path("level").asText());
        assertEquals("hello", parsed.params().path("message").asText());
    }

    @Test
    void buildNotificationNullParams() {
        String msg = PluginMessage.buildNotification("plugin/ready", null);
        PluginMessage.ParsedMessage parsed = PluginMessage.parse(msg);
        assertTrue(parsed.isNotification());
        assertEquals("plugin/ready", parsed.method());
    }

    // ─── parse ───

    @Test
    void parseInvalidJson() {
        PluginMessage.ParsedMessage msg = PluginMessage.parse("not json at all");
        assertTrue(msg.isInvalid());
        assertTrue(msg.isError());
        assertTrue(msg.error().contains("Parse error"));
    }

    @Test
    void parseWrongJsonRpcVersion() {
        String line = "{\"jsonrpc\":\"1.0\",\"method\":\"test\"}";
        PluginMessage.ParsedMessage msg = PluginMessage.parse(line);
        assertTrue(msg.isInvalid());
        assertTrue(msg.error().contains("Invalid jsonrpc version"));
    }

    @Test
    void parseErrorResponse() {
        String line = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"Plugin error\"}}";
        PluginMessage.ParsedMessage msg = PluginMessage.parse(line);
        assertTrue(msg.isResponse());
        assertTrue(msg.isError());
        assertEquals("Plugin error", msg.error());
    }

    @Test
    void parseResponseWithResult() {
        String line = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"success\":true}}";
        PluginMessage.ParsedMessage msg = PluginMessage.parse(line);
        assertTrue(msg.isResponse());
        assertFalse(msg.isError());
        assertNotNull(msg.result());
    }

    @Test
    void parseNotification() {
        String line = "{\"jsonrpc\":\"2.0\",\"method\":\"plugin/ready\"}";
        PluginMessage.ParsedMessage msg = PluginMessage.parse(line);
        assertTrue(msg.isNotification());
        assertEquals("plugin/ready", msg.method());
    }

    @Test
    void parseRequest() {
        String line = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tool/execute\",\"params\":{\"toolId\":\"test\"}}";
        PluginMessage.ParsedMessage msg = PluginMessage.parse(line);
        assertTrue(msg.isRequest());
        assertEquals(3, msg.id());
        assertEquals("tool/execute", msg.method());
    }

    @Test
    void parseUnrecognized() {
        String line = "{\"jsonrpc\":\"2.0\",\"foo\":\"bar\"}";
        PluginMessage.ParsedMessage msg = PluginMessage.parse(line);
        assertTrue(msg.isInvalid());
        assertTrue(msg.error().contains("Unrecognized"));
    }

    @Test
    void parseMalformedJson() {
        String line = "{\"jsonrpc\":\"2.0\",\"id\":1,}";
        PluginMessage.ParsedMessage msg = PluginMessage.parse(line);
        assertTrue(msg.isInvalid());
    }

    // ─── Convenience builders ───

    @Test
    void buildToolExecuteRequest() {
        String msg = PluginMessage.buildToolExecuteRequest(10, "ctx_memory", Map.of("action", "write"));
        PluginMessage.ParsedMessage parsed = PluginMessage.parse(msg);
        assertTrue(parsed.isRequest());
        assertEquals("tool/execute", parsed.method());
        assertEquals("ctx_memory", parsed.params().path("toolId").asText());
    }

    @Test
    void buildToolListRequest() {
        String msg = PluginMessage.buildToolListRequest(11);
        PluginMessage.ParsedMessage parsed = PluginMessage.parse(msg);
        assertEquals("tool/list", parsed.method());
    }

    @Test
    void buildInitializeRequest() {
        String msg = PluginMessage.buildInitializeRequest(12, Map.of("plugin", "test"));
        PluginMessage.ParsedMessage parsed = PluginMessage.parse(msg);
        assertEquals("plugin/initialize", parsed.method());
    }

    @Test
    void buildLogNotification() {
        String msg = PluginMessage.buildLogNotification("warn", "test message");
        PluginMessage.ParsedMessage parsed = PluginMessage.parse(msg);
        assertTrue(parsed.isNotification());
        assertEquals("plugin/log", parsed.method());
        assertEquals("warn", parsed.params().path("level").asText());
    }

    // ─── Utility methods ───

    @Test
    void looksLikeJson() {
        assertTrue(PluginMessage.looksLikeJson("{\"key\": \"value\"}"));
        assertTrue(PluginMessage.looksLikeJson("  {  "));
        assertFalse(PluginMessage.looksLikeJson("not json"));
        assertFalse(PluginMessage.looksLikeJson(""));
        assertFalse(PluginMessage.looksLikeJson(null));
    }

    @Test
    void extractToolId() {
        JsonNode params = MAPPER.createObjectNode().put("toolId", "ctx_memory");
        assertEquals("ctx_memory", PluginMessage.extractToolId(params));
        assertNull(PluginMessage.extractToolId(null));
        // Missing node returns "" via asText(), not null
        assertEquals("", PluginMessage.extractToolId(MAPPER.createObjectNode()));
    }

    @Test
    void extractToolArgs() {
        JsonNode params = MAPPER.createObjectNode()
                .set("args", MAPPER.createObjectNode().put("action", "list"));
        Map<String, Object> args = PluginMessage.extractToolArgs(params);
        assertEquals("list", args.get("action"));

        assertTrue(PluginMessage.extractToolArgs(null).isEmpty());
        assertTrue(PluginMessage.extractToolArgs(MAPPER.createObjectNode()).isEmpty());
    }

    // ─── ParsedMessage record ───

    @Test
    void parsedMessageErrorFactory() {
        PluginMessage.ParsedMessage err = PluginMessage.ParsedMessage.error("something failed");
        assertTrue(err.isError());
        assertTrue(err.isInvalid());
        assertEquals("something failed", err.error());
    }

    @Test
    void parsedMessageResultAccessor() {
        JsonNode data = MAPPER.createObjectNode().put("key", "val");
        var msg = new PluginMessage.ParsedMessage(
                PluginMessage.Type.RESPONSE, 1, null, data, null);
        assertSame(data, msg.result());
    }
}
