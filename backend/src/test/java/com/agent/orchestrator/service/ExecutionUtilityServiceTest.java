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
    @Mock Neo4jSchemaRepository schemaRepository;
    @Mock ProjectContextBuilder projectContextBuilder;
    @Mock ExecutionStateManager stateManager;

    ExecutionUtilityService utilityService;

    @BeforeEach
    void setUp() {
        utilityService = new ExecutionUtilityService(llmService, webSocketHandler, memPalaceClient,
                schemaRepository, projectContextBuilder, stateManager,
                new NodeSourceHandler(webSocketHandler, memPalaceClient, schemaRepository, projectContextBuilder, stateManager),
                new NodeCommandExecutor(webSocketHandler),
                new NodeFileWriter());
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
        assertTrue(utilityService.evaluateCondition("true", Map.of()));
    }

    @Test
    void evaluateCondition_falseExpression_returnsFalse() {
        assertFalse(utilityService.evaluateCondition("false", Map.of()));
    }

    @Test
    void evaluateCondition_nullExpression_returnsFalse() {
        assertFalse(utilityService.evaluateCondition(null, Map.of()));
    }

    @Test
    void evaluateCondition_blankExpression_returnsFalse() {
        assertFalse(utilityService.evaluateCondition("", Map.of()));
    }

    @Test
    void evaluateCondition_withVariableComparison() {
        Map<String, Object> ctx = Map.of("iterations", 3);
        assertTrue(utilityService.evaluateCondition("iterations < 5", ctx));
    }

    @Test
    void evaluateCondition_withStringComparison() {
        Map<String, Object> ctx = Map.of("result", "hello");
        assertTrue(utilityService.evaluateCondition("result == 'hello'", ctx));
    }

    @Test
    void evaluateCondition_withFalseComparison() {
        Map<String, Object> ctx = Map.of("iterations", 10);
        assertFalse(utilityService.evaluateCondition("iterations < 5", ctx));
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
