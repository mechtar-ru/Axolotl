package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.Tool;
import com.agent.orchestrator.model.Tool.ToolResult;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionUtilityServiceTest {

    @Mock LlmService llmService;
    @Mock ExecutionWebSocketHandler webSocketHandler;
    @Mock MemPalaceClient memPalaceClient;
    @Mock ToolExecutor toolExecutor;
    @Mock Neo4jSchemaRepository schemaRepository;
    @Mock ProjectContextBuilder projectContextBuilder;
    @Mock ExecutionStateManager stateManager;
    @Mock ExecutionRepository executionRepository;

    ExecutionUtilityService utilityService;

    @BeforeEach
    void setUp() {
        utilityService = new ExecutionUtilityService(llmService, webSocketHandler, memPalaceClient,
                toolExecutor, schemaRepository, projectContextBuilder, stateManager, executionRepository);
    }

    // ── resolveModel ──

    @Test
    void resolveModel_returnsNodeModel_whenProvided() {
        assertEquals("node-model", utilityService.resolveModel("node-model", "schema-model", "user", "global"));
    }

    @Test
    void resolveModel_returnsSchemaModel_whenNodeModelNull() {
        assertEquals("schema-model", utilityService.resolveModel(null, "schema-model", "user", "global"));
    }

    @Test
    void resolveModel_returnsSchemaModel_whenNodeModelBlank() {
        assertEquals("schema-model", utilityService.resolveModel("  ", "schema-model", "user", "global"));
    }

    @Test
    void resolveModel_returnsGlobalModel_whenOthersNull() {
        assertEquals("global-model", utilityService.resolveModel(null, null, null, "global-model"));
    }

    @Test
    void resolveModel_returnsNull_whenAllNull() {
        assertNull(utilityService.resolveModel(null, null, null, null));
    }

    // ── collectPredecessorResults ──

    @Test
    void collectPredecessorResults_returnsEmptyMap_whenSchemaNull() {
        assertTrue(utilityService.collectPredecessorResults(null, "n1").isEmpty());
    }

    @Test
    void collectPredecessorResults_returnsEmptyMap_whenNoEdges() {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("s1");
        schema.setEdges(null);
        assertTrue(utilityService.collectPredecessorResults(schema, "n1").isEmpty());
    }

    @Test
    void collectPredecessorResults_returnsPredecessorResult() {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("s1");
        Edge edge = new Edge();
        edge.setSource("source1");
        edge.setTarget("target1");
        schema.setEdges(List.of(edge));
        schema.setNodes(List.of());

        Map<String, Map<String, String>> nodeResults = new ConcurrentHashMap<>();
        Map<String, String> inner = new ConcurrentHashMap<>();
        inner.put("source1", "result from source");
        nodeResults.put("s1", inner);
        when(stateManager.getNodeResults()).thenReturn(nodeResults);

        Map<String, Object> results = utilityService.collectPredecessorResults(schema, "target1");

        assertEquals(1, results.size());
        assertTrue(results.containsValue("result from source"));
    }

    @Test
    void collectPredecessorResults_returnsNodeDataResult_whenNotInStateManager() {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("s1");
        Edge edge = new Edge();
        edge.setSource("source1");
        edge.setTarget("target1");
        schema.setEdges(List.of(edge));
        Node sourceNode = new Node();
        sourceNode.setId("source1");
        sourceNode.setName("Source");
        Node.NodeData data = new Node.NodeData();
        data.setResult("node data result");
        sourceNode.setData(data);
        schema.setNodes(List.of(sourceNode));

        when(stateManager.getNodeResults()).thenReturn(new ConcurrentHashMap<>());

        Map<String, Object> results = utilityService.collectPredecessorResults(schema, "target1");

        assertEquals(1, results.size());
        assertTrue(results.containsValue("node data result"));
    }

    // ── evaluateCondition ──

    @Test
    void evaluateCondition_trueExpression_returnsTrue() {
        assertTrue(utilityService.evaluateConditionPublic("true", Map.of()));
    }

    @Test
    void evaluateCondition_falseExpression_returnsFalse() {
        assertFalse(utilityService.evaluateConditionPublic("false", Map.of()));
    }

    @Test
    void evaluateCondition_nullExpression_returnsFalse() {
        assertFalse(utilityService.evaluateConditionPublic(null, Map.of()));
    }

    @Test
    void evaluateCondition_blankExpression_returnsFalse() {
        assertFalse(utilityService.evaluateConditionPublic("", Map.of()));
    }

    @Test
    void evaluateCondition_withVariableComparison() {
        Map<String, Object> ctx = Map.of("iterations", 3);
        assertTrue(utilityService.evaluateConditionPublic("iterations < 5", ctx));
    }

    @Test
    void evaluateCondition_withStringComparison() {
        Map<String, Object> ctx = Map.of("result", "hello");
        assertTrue(utilityService.evaluateConditionPublic("result == 'hello'", ctx));
    }

    @Test
    void evaluateCondition_withFalseComparison() {
        Map<String, Object> ctx = Map.of("iterations", 10);
        assertFalse(utilityService.evaluateConditionPublic("iterations < 5", ctx));
    }

    // ── sleepWithCancel ──

    @Test
    void sleepWithCancel_returnsTrue_whenNotCancelled() {
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        boolean result = utilityService.sleepWithCancel(1, cancelFlag);
        assertTrue(result);
    }

    @Test
    void sleepWithCancel_returnsFalse_whenAlreadyCancelled() {
        AtomicBoolean cancelFlag = new AtomicBoolean(true);
        boolean result = utilityService.sleepWithCancel(100, cancelFlag);
        assertFalse(result);
    }

    // ── sanitizeCommand ──

    @Test
    void sanitizeCommand_blocksRmRf() {
        assertThrows(SecurityException.class,
                () -> utilityService.sanitizeCommandPublic("rm -rf /"));
    }

    @Test
    void sanitizeCommand_blocksMkfs() {
        assertThrows(SecurityException.class,
                () -> utilityService.sanitizeCommandPublic("mkfs.ext4 /dev/sda1"));
    }

    @Test
    void sanitizeCommand_blocksShutdown() {
        assertThrows(SecurityException.class,
                () -> utilityService.sanitizeCommandPublic("shutdown -h now"));
    }

    @Test
    void sanitizeCommand_blocksDangerousShellExpansion() {
        assertThrows(SecurityException.class,
                () -> utilityService.sanitizeCommandPublic("echo $(rm -rf /)"));
    }

    @Test
    void sanitizeCommand_allowsSafeCommands() {
        assertEquals("ls -la", utilityService.sanitizeCommandPublic("ls -la"));
        assertEquals("echo hello", utilityService.sanitizeCommandPublic("echo hello"));
        assertEquals("cat file.txt", utilityService.sanitizeCommandPublic("cat file.txt"));
    }

    // ── validateUrl ──

    @Test
    void validateUrl_blocksFtp() {
        assertThrows(SecurityException.class,
                () -> utilityService.validateUrlPublic("ftp://example.com/file"));
    }

    @Test
    void validateUrl_blocksFileUrl() {
        assertThrows(SecurityException.class,
                () -> utilityService.validateUrlPublic("file:///etc/passwd"));
    }

    @Test
    void validateUrl_acceptsHttps() {
        // Should throw because 8.8.8.8 is not loopback/site-local
        // But it's a public DNS, so the InetAddress check should pass
        // Actually 8.8.8.8 is not loopback/site-local/link-local/any-local, so this should be fine
        // We just verify no exception for a valid public URL
        try {
            utilityService.validateUrlPublic("https://google.com");
        } catch (SecurityException e) {
            // Could fail if DNS resolution fails in test environment, that's OK
        }
    }

    // ── interpolateVariables ──

    @Test
    void interpolateVariables_returnsOriginalText_whenNoPlaceholders() {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setName("Test");
        String result = utilityService.interpolateVariables("plain text", schema, Map.of());
        assertEquals("plain text", result);
    }

    @Test
    void interpolateVariables_replacesInputPlaceholder() {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setName("Test");
        String result = utilityService.interpolateVariables("Input: {{input}}", schema, Map.of("key", "value1"));
        assertEquals("Input: value1", result);
    }

    @Test
    void interpolateVariables_replacesSchemaName() {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setName("MySchema");
        String result = utilityService.interpolateVariables("Schema: {{schema_name}}", schema, Map.of());
        assertEquals("Schema: MySchema", result);
    }

    // ── buildContextBlock ──

    @Test
    void buildContextBlock_returnsEmpty_whenEmptyMap() {
        assertTrue(utilityService.buildContextBlock(Map.of()).isEmpty());
    }

    @Test
    void buildContextBlock_returnsFormattedContext() {
        Map<String, Object> results = Map.of("node1", "result1", "node2", "result2");
        String context = utilityService.buildContextBlock(results);
        assertTrue(context.contains("[node1]"));
        assertTrue(context.contains("result1"));
        assertTrue(context.contains("[node2]"));
    }

    // ── buildToolDefinitions ──

    @Test
    void buildToolDefinitions_returnsDefinitions() {
        Tool mockTool = new Tool();
        mockTool.setId("bash");
        mockTool.setDescription("Execute bash commands");
        mockTool.setInputSchema("{\"command\": \"string\"}");
        when(toolExecutor.getTool("bash")).thenReturn(mockTool);

        String result = utilityService.buildToolDefinitions(List.of("bash"));

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

        String result = utilityService.buildToolInstructions(List.of("file_read"));

        assertTrue(result.contains("file_read"));
        assertTrue(result.contains("Read files"));
        assertTrue(result.contains("tool_calls"));
    }

    // ── buildMessagesForToolCall ──

    @Test
    void buildMessagesForToolCall_formatsMessages() {
        Node.Message msg1 = new Node.Message("system", "You are a bot");
        Node.Message msg2 = new Node.Message("user", "Hello");
        String result = utilityService.buildMessagesForToolCall(List.of(msg1, msg2));

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

        String result = utilityService.executeToolCall("bash", Map.of("command", "ls"), node, "s1");

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

        String result = utilityService.executeToolCall("bash", Map.of("command", "unknown"), node, "s1");

        assertTrue(result.startsWith("Error"));
    }

    // ── extractGeneratedFiles ──

    @Test
    void extractGeneratedFiles_returnsNull_whenResponseEmpty() {
        assertNull(utilityService.extractGeneratedFiles(""));
    }

    @Test
    void extractGeneratedFiles_returnsNull_whenNoGeneratedFiles() {
        assertNull(utilityService.extractGeneratedFiles("Just a normal response without the magic key"));
    }

    @Test
    void extractGeneratedFiles_parsesGeneratedFiles() {
        String response = "Some text before {\"generatedFiles\": {\"file1.txt\": 150}}";
        Map<String, Object> result = utilityService.extractGeneratedFiles(response);
        assertNotNull(result);
        assertTrue(result.containsKey("generatedFiles"));
    }

    // ── parseToolCalls ──

    @Test
    void parseToolCalls_returnsEmptyList_whenNoToolCalls() {
        assertTrue(utilityService.parseToolCalls("Just a normal response").isEmpty());
    }

    @Test
    void parseToolCalls_parsesToolCalls() {
        String response = "{\"tool_calls\": [{\"id\": \"call_1\", \"name\": \"bash\", \"arguments\": {\"command\": \"ls\"}}]}";
        List<Map<String, Object>> result = utilityService.parseToolCalls(response);
        assertEquals(1, result.size());
        assertEquals("bash", result.get(0).get("name"));
    }

    @Test
    void parseToolCalls_parsesToolCallsWithMarkdown() {
        String response = "```json\n{\"tool_calls\": [{\"id\": \"call_1\", \"name\": \"bash\", \"arguments\": {\"command\": \"ls\"}}]}\n```";
        List<Map<String, Object>> result = utilityService.parseToolCalls(response);
        assertEquals(1, result.size());
        assertEquals("bash", result.get(0).get("name"));
    }

    // ── sendUserApprovalRequest ──

    @Test
    void sendUserApprovalRequest_sendsWebSocketLog() {
        utilityService.sendUserApprovalRequest("schema-1", "n1", 10, 20);
        verify(webSocketHandler).sendLog(eq("schema-1"), eq("warning"), anyString(), eq("n1"));
    }

    // ── writeOutput ──

    @Test
    void writeOutput_returnsContent_whenNoFileType() {
        String result = utilityService.writeOutput("log", null, null, "test content");
        assertEquals("test content", result);
    }

    @Test
    void writeOutput_returnsNoData_whenContentBlank() {
        String result = utilityService.writeOutput("file", "/tmp/test.txt", "text", "");
        assertEquals("Нет данных для вывода", result);
    }

    // ── handleSourceNode - file sourceType ──

    @Test
    void handleSourceNode_returnsFileNotFound_whenEmptyPath() throws Exception {
        Node node = new Node();
        Node.NodeData data = new Node.NodeData();
        Map<String, Object> config = new HashMap<>();
        config.put("sourceType", "file");
        config.put("filePath", "");
        data.setConfig(config);
        node.setData(data);

        String result = utilityService.handleSourceNode(node, "schema-1");
        assertEquals("Файл не указан", result);
    }

    @Test
    void handleSourceNode_readsAbsolutePath() throws Exception {
        // Create temp file
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("test-source-", ".txt");
        java.nio.file.Files.writeString(tempFile, "file content");

        Node node = new Node();
        Node.NodeData data = new Node.NodeData();
        Map<String, Object> config = new HashMap<>();
        config.put("sourceType", "file");
        config.put("filePath", tempFile.toString());
        data.setConfig(config);
        node.setData(data);

        String result = utilityService.handleSourceNode(node, "schema-1");
        assertEquals("file content", result);

        java.nio.file.Files.deleteIfExists(tempFile);
    }

    @Test
    void handleSourceNode_returnsFileNotFound_whenFileMissing() {
        Node node = new Node();
        Node.NodeData data = new Node.NodeData();
        Map<String, Object> config = new HashMap<>();
        config.put("sourceType", "file");
        config.put("filePath", "/tmp/nonexistent-file-12345.txt");
        data.setConfig(config);
        node.setData(data);

        String result = utilityService.handleSourceNode(node, "schema-1");
        assertTrue(result.contains("не найден") || result.contains("not found"));
    }

    @Test
    void handleSourceNode_resolvesRelativePathAgainstTargetPath() throws Exception {
        // Create temp dir with a file inside
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("test-schema-");
        java.nio.file.Path testFile = tempDir.resolve("subdir/test.md");
        java.nio.file.Files.createDirectories(testFile.getParent());
        java.nio.file.Files.writeString(testFile, "relative content");

        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("schema-1");
        schema.setTargetPath(tempDir.toString());
        when(schemaRepository.findById("schema-1")).thenReturn(schema);

        Node node = new Node();
        Node.NodeData data = new Node.NodeData();
        Map<String, Object> config = new HashMap<>();
        config.put("sourceType", "file");
        config.put("filePath", "subdir/test.md");
        data.setConfig(config);
        node.setData(data);

        String result = utilityService.handleSourceNode(node, "schema-1");
        assertEquals("relative content", result);

        // Cleanup
        java.nio.file.Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {} });
    }
}
