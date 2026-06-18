package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.Tool;
import com.agent.orchestrator.model.Tool.ToolResult;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ToolExecutionServiceTest {

    @Mock ToolExecutor toolExecutor;
    @Mock ExecutionWebSocketHandler webSocketHandler;

    ToolExecutionService service;

    @BeforeEach
    void setUp() {
        service = new ToolExecutionService(toolExecutor, webSocketHandler, new ToolCallParser(), new ObjectMapper());
    }

    // ── buildToolDefinitions ──

    @Test
    void buildToolDefinitions_returnsDefinitions() {
        Tool mockTool = new Tool();
        mockTool.setId("bash");
        mockTool.setDescription("Execute bash commands");
        mockTool.setInputSchema("{\"command\": \"string\"}");
        when(toolExecutor.getTool("bash")).thenReturn(mockTool);

        String result = service.buildToolDefinitions(List.of("bash"));

        assertTrue(result.contains("bash"));
        assertTrue(result.contains("Execute bash commands"));
    }

    // ── buildToolInstructions ──

    @Test
    void buildToolInstructions_returnsInstructions() {
        Tool mockTool = new Tool();
        mockTool.setId("file_read");
        mockTool.setDescription("Read files");
        when(toolExecutor.getTool("file_read")).thenReturn(mockTool);

        String result = service.buildToolInstructions(List.of("file_read"));

        assertTrue(result.contains("file_read"));
        assertTrue(result.contains("Read files"));
        assertTrue(result.contains("tool_calls"));
    }

    // ── buildMessagesForToolCall ──

    @Test
    void buildMessagesForToolCall_formatsMessages() {
        Node.Message msg1 = new Node.Message("system", "You are a bot");
        Node.Message msg2 = new Node.Message("user", "Hello");
        String result = service.buildMessagesForToolCall(List.of(msg1, msg2));

        assertTrue(result.contains("system"));
        assertTrue(result.contains("You are a bot"));
        assertTrue(result.contains("user"));
        assertTrue(result.contains("Hello"));
    }

    // ── executeToolCall ──

    @Test
    void executeToolCall_usesToolPermissions() {
        Node node = new Node();
        Node.NodeData data = new Node.NodeData();
        data.setEnabledTools(List.of("bash"));
        List<com.agent.orchestrator.model.ToolPermission> perms = List.of(
                new com.agent.orchestrator.model.ToolPermission("bash")
        );
        perms.get(0).setEnabled(true);
        data.setToolPermissions(perms);
        node.setData(data);

        ToolResult toolResult = new ToolResult(true, "output", null);
        when(toolExecutor.execute(eq("bash"), anyMap(), any(), eq("s1"), any(), any(), any())).thenReturn(toolResult);

        String result = service.executeToolCall("bash", Map.of("command", "ls"), node, "s1");

        assertEquals("output", result);
        verify(toolExecutor).execute(eq("bash"), anyMap(), any(), eq("s1"), any(), any(), any());
    }

    @Test
    void executeToolCall_returnsError_whenToolFails() {
        Node node = new Node();
        Node.NodeData data = new Node.NodeData();
        data.setEnabledTools(List.of("bash"));
        node.setData(data);

        ToolResult toolResult = new ToolResult(false, null, "command not found");
        when(toolExecutor.execute(eq("bash"), anyMap(), any(), eq("s1"), any(), any(), any())).thenReturn(toolResult);

        String result = service.executeToolCall("bash", Map.of("command", "unknown"), node, "s1");

        assertTrue(result.startsWith("Error"));
    }

    // ── extractGeneratedFiles ──

    @Test
    void extractGeneratedFiles_returnsNull_whenResponseEmpty() {
        assertNull(service.extractGeneratedFiles(""));
    }

    @Test
    void extractGeneratedFiles_returnsNull_whenNoGeneratedFiles() {
        assertNull(service.extractGeneratedFiles("Just a normal response without the magic key"));
    }

    @Test
    void extractGeneratedFiles_parsesGeneratedFiles() {
        String response = "Some text before {\"generatedFiles\": {\"file1.txt\": 150}}";
        Map<String, Object> result = service.extractGeneratedFiles(response);
        assertNotNull(result);
        assertTrue(result.containsKey("generatedFiles"));
    }

    // ── parseToolCalls ──

    @Test
    void parseToolCalls_returnsEmptyList_whenNoToolCalls() {
        assertTrue(service.parseToolCalls("Just a normal response").isEmpty());
    }

    @Test
    void parseToolCalls_parsesToolCalls() {
        String response = "{\"tool_calls\": [{\"id\": \"call_1\", \"name\": \"bash\", \"arguments\": {\"command\": \"ls\"}}]}";
        List<Map<String, Object>> result = service.parseToolCalls(response);
        assertEquals(1, result.size());
        assertEquals("bash", result.get(0).get("name"));
    }

    @Test
    void parseToolCalls_parsesToolCallsWithMarkdown() {
        String response = "```json\n{\"tool_calls\": [{\"id\": \"call_1\", \"name\": \"bash\", \"arguments\": {\"command\": \"ls\"}}]}\n```";
        List<Map<String, Object>> result = service.parseToolCalls(response);
        assertEquals(1, result.size());
        assertEquals("bash", result.get(0).get("name"));
    }

    // ── sendUserApprovalRequest ──

    @Test
    void sendUserApprovalRequest_sendsWebSocketLog() {
        service.sendUserApprovalRequest("schema-1", "n1", 10, 20);
        verify(webSocketHandler).sendLog(eq("schema-1"), eq("warning"), anyString(), eq("n1"));
    }
}
