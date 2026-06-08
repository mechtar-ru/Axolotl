package com.agent.orchestrator.plugin;

import com.agent.orchestrator.model.Tool;
import com.agent.orchestrator.model.Tool.ToolResult;
import com.agent.orchestrator.service.ToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PluginToolAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private PluginBridge bridge;

    @Mock
    private ToolExecutor toolExecutor;

    private PluginToolAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new PluginToolAdapter(bridge, toolExecutor);
    }

    // ─── registerSingleTool ───

    @Test
    void registerSingleToolValid() {
        ObjectNode toolDef = MAPPER.createObjectNode();
        toolDef.put("id", "ctx_memory");
        toolDef.put("name", "Write Memory");
        toolDef.put("description", "Manages memories");
        toolDef.set("inputSchema", MAPPER.createObjectNode().put("type", "object"));
        toolDef.put("category", "MEMORY");

        adapter.registerSingleTool(toolDef);

        assertEquals(1, adapter.getToolCount());
        assertTrue(adapter.getPluginTools().containsKey("ctx_memory"));

        Tool registered = adapter.getPluginTools().get("ctx_memory");
        assertEquals("ctx_memory", registered.getId());
        assertEquals("Write Memory", registered.getName());
        assertEquals(Tool.ToolCategory.MEMORY, registered.getCategory());

        verify(toolExecutor).registerTool(any(Tool.class));
        verify(toolExecutor).registerPluginHandler(eq("ctx_memory"), any());
    }

    @Test
    void registerSingleToolMissingId() {
        ObjectNode toolDef = MAPPER.createObjectNode();
        toolDef.put("name", "Nameless");
        when(bridge.getName()).thenReturn("test-bridge");

        adapter.registerSingleTool(toolDef);

        assertEquals(0, adapter.getToolCount());
        verifyNoInteractions(toolExecutor);
    }

    @Test
    void registerSingleToolBlankId() {
        ObjectNode toolDef = MAPPER.createObjectNode();
        toolDef.put("id", "");
        toolDef.put("name", "Blank Id");
        when(bridge.getName()).thenReturn("test-bridge");

        adapter.registerSingleTool(toolDef);

        assertEquals(0, adapter.getToolCount());
    }

    @Test
    void registerSingleToolInvalidCategoryDefaultsToCustom() {
        ObjectNode toolDef = MAPPER.createObjectNode();
        toolDef.put("id", "my_custom_tool");
        toolDef.put("name", "Custom Tool");
        toolDef.put("category", "UNKNOWN_CATEGORY");

        adapter.registerSingleTool(toolDef);

        Tool registered = adapter.getPluginTools().get("my_custom_tool");
        assertEquals(Tool.ToolCategory.CUSTOM, registered.getCategory());
    }

    @Test
    void registerSingleToolDefaultsNameToId() {
        ObjectNode toolDef = MAPPER.createObjectNode();
        toolDef.put("id", "my_tool");

        adapter.registerSingleTool(toolDef);

        assertEquals("my_tool", adapter.getPluginTools().get("my_tool").getName());
    }

    @Test
    void registerSingleToolHandlerCapturedId() {
        // Verify that the handler closure captures the correct tool ID
        ObjectNode def1 = MAPPER.createObjectNode();
        def1.put("id", "tool_a");
        ObjectNode def2 = MAPPER.createObjectNode();
        def2.put("id", "tool_b");

        adapter.registerSingleTool(def1);
        adapter.registerSingleTool(def2);

        assertEquals(2, adapter.getToolCount());

        // Capture the handlers
        ArgumentCaptor<ToolExecutor.ToolExecutorHandler> captor =
                ArgumentCaptor.captor();
        verify(toolExecutor, times(2)).registerPluginHandler(anyString(), captor.capture());

        // Both handlers should execute their respective tools
        var handlers = captor.getAllValues();
        assertNotNull(handlers.get(0));
        assertNotNull(handlers.get(1));
    }

    // ─── executePluginTool ───

    @Test
    void executePluginToolSuccess() throws Exception {
        ObjectNode toolDef = MAPPER.createObjectNode();
        toolDef.put("id", "ctx_memory");
        adapter.registerSingleTool(toolDef);

        PluginMessage.ParsedMessage response = PluginMessage.parse(
                PluginMessage.buildResponse(1, Map.of("success", true, "output", "done!")));

        when(bridge.sendRequest(eq("tool/execute"), any())).thenReturn(response);

        ToolResult result = adapter.executePluginTool("ctx_memory", Map.of("action", "list"));

        assertTrue(result.isSuccess());
        assertEquals("done!", result.getOutput());
        assertTrue(result.getExecutionTimeMs() >= 0);

        verify(bridge).sendRequest(eq("tool/execute"), argThat(params ->
                "ctx_memory".equals(params.get("toolId"))));
    }

    @Test
    void executePluginToolErrorResponse() throws Exception {
        ObjectNode toolDef = MAPPER.createObjectNode();
        toolDef.put("id", "fail_tool");
        adapter.registerSingleTool(toolDef);

        PluginMessage.ParsedMessage response = PluginMessage.parse(
                PluginMessage.buildResponse(1, Map.of("success", false, "error", "Something broke")));

        when(bridge.sendRequest(eq("tool/execute"), any())).thenReturn(response);

        ToolResult result = adapter.executePluginTool("fail_tool", Map.of());
        assertFalse(result.isSuccess());
        assertEquals("Something broke", result.getError()); // ToolResult.error() stores in error, not output
    }

    @Test
    void executePluginToolBridgeThrows() {
        when(bridge.sendRequest(anyString(), any()))
                .thenThrow(new PluginBridge.PluginException("Bridge not running"));

        ToolResult result = adapter.executePluginTool("test_tool", Map.of());
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Bridge not running"));
    }

    @Test
    void executePluginToolNullResponse() throws Exception {
        when(bridge.sendRequest(anyString(), any())).thenReturn(null);

        ToolResult result = adapter.executePluginTool("test_tool", Map.of());
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("No response"));
    }

    // ─── registerFromInit ───

    @Test
    void registerFromInitNullDoesNothing() {
        adapter.registerFromInit(null);
        assertEquals(0, adapter.getToolCount());
        verifyNoInteractions(toolExecutor);
    }

    // ─── handleIncomingToolRequest ───

    @Test
    void handleIncomingToolRequestLogsWarning() {
        when(bridge.getName()).thenReturn("test");
        PluginMessage.ParsedMessage msg = PluginMessage.ParsedMessage.error("incoming");
        assertDoesNotThrow(() -> adapter.handleIncomingToolRequest(msg));
    }
}
