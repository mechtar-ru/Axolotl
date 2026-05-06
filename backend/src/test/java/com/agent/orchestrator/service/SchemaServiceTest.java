package com.agent.orchestrator.service;

import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.model.*;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.agent.orchestrator.service.ToolExecutor;
import com.agent.orchestrator.service.MetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SchemaServiceTest {

    @Mock Neo4jSchemaRepository schemaRepository;
    @Mock LlmService llmService;
    @Mock ExecutionWebSocketHandler webSocketHandler;
    @Mock MemPalaceClient memPalaceClient;
    @Mock PlanService planService;
    @Mock SettingsService settingsService;
    @Mock ToolExecutor toolExecutor;
    @Mock MetricsService metricsService;

    SchemaService schemaService;

    @BeforeEach
    void setUp() {
        schemaService = new SchemaService(schemaRepository, llmService, webSocketHandler, memPalaceClient, planService, settingsService, toolExecutor, metricsService);
    }

    // === Topological Sort / Execution Levels ===

    @Test
    void getExecutionLevels_linearGraph() {
        // A → B → C
        WorkflowSchema schema = buildSchema(
            nodes("a", "b", "c"),
            edges("a->b", "b->c")
        );

        var levels = schemaService.getExecutionLevelsPublic(schema);

        assertEquals(3, levels.size());
        assertEquals(List.of("a"), names(levels.get(0)));
        assertEquals(List.of("b"), names(levels.get(1)));
        assertEquals(List.of("c"), names(levels.get(2)));
    }

    @Test
    void getExecutionLevels_diamondGraph() {
        // A → B, A → C, B → D, C → D
        WorkflowSchema schema = buildSchema(
            nodes("a", "b", "c", "d"),
            edges("a->b", "a->c", "b->d", "c->d")
        );

        var levels = schemaService.getExecutionLevelsPublic(schema);

        assertEquals(3, levels.size());
        assertEquals(List.of("a"), names(levels.get(0)));
        // B and C are on the same level (parallel)
        assertEquals(2, levels.get(1).size());
        assertTrue(names(levels.get(1)).containsAll(List.of("b", "c")));
        assertEquals(List.of("d"), names(levels.get(2)));
    }

    @Test
    void getExecutionLevels_singleNode() {
        WorkflowSchema schema = buildSchema(nodes("a"), edges());

        var levels = schemaService.getExecutionLevelsPublic(schema);

        assertEquals(1, levels.size());
        assertEquals(List.of("a"), names(levels.get(0)));
    }

    @Test
    void getExecutionLevels_emptySchema() {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setNodes(List.of());
        schema.setEdges(List.of());

        var levels = schemaService.getExecutionLevelsPublic(schema);

        assertTrue(levels.isEmpty());
    }

    @Test
    void getExecutionLevels_disconnectedNodes() {
        // A, B, C — no edges
        WorkflowSchema schema = buildSchema(nodes("a", "b", "c"), edges());

        var levels = schemaService.getExecutionLevelsPublic(schema);

        assertEquals(1, levels.size());
        assertEquals(3, levels.get(0).size());
    }

    // === Variable Interpolation ===

    @Test
    void interpolateVariables_input() {
        WorkflowSchema s = buildSchema(nodes("a"), edges());
        Map<String, Object> preds = Map.of("Source", "hello world");
        String result = schemaService.interpolateVariablesPublic(
            "Process: {{input}}", s, preds);
        assertEquals("Process: hello world", result);
    }

    @Test
    void interpolateVariables_prevResult() {
        WorkflowSchema s = buildSchema(nodes("a"), edges());
        Map<String, Object> preds = new LinkedHashMap<>();
        preds.put("First", "data1");
        preds.put("Second", "data2");
        String result = schemaService.interpolateVariablesPublic(
            "Use: {{prev_result}}", s, preds);
        assertEquals("Use: data2", result);
    }

    @Test
    void interpolateVariables_nodeByName() {
        // Set up a schema with a node that has a result
        Node node = new Node();
        node.setId("n1");
        node.setName("MyNode");
        Node.NodeData data = new Node.NodeData();
        data.setResult("node output");
        node.setData(data);

        WorkflowSchema s = new WorkflowSchema();
        s.setNodes(List.of(node));
        s.setEdges(List.of());

        String result = schemaService.interpolateVariablesPublic(
            "Ref: {{node:MyNode}}", s, Map.of());
        assertEquals("Ref: node output", result);
    }

    @Test
    void interpolateVariables_schemaName() {
        WorkflowSchema s = buildSchema(nodes("a"), edges());
        s.setName("My Workflow");
        String result = schemaService.interpolateVariablesPublic(
            "Workflow: {{schema_name}}", s, Map.of());
        assertEquals("Workflow: My Workflow", result);
    }

    @Test
    void interpolateVariables_noVariables() {
        WorkflowSchema s = buildSchema(nodes("a"), edges());
        String result = schemaService.interpolateVariablesPublic(
            "No vars here", s, Map.of());
        assertEquals("No vars here", result);
    }

    @Test
    void interpolateVariables_nullInput() {
        String result = schemaService.interpolateVariablesPublic(null, schema, Map.of());
        assertNull(result);
    }

    // === Context Compression ===

    @Test
    void buildContextBlock_shortContext() {
        Map<String, Object> preds = Map.of("Source", "Short data");
        String result = schemaService.buildContextBlockPublic(preds);
        assertTrue(result.contains("[Source]: Short data"));
    }

    @Test
    void buildContextBlock_emptyInput() {
        String result = schemaService.buildContextBlockPublic(Map.of());
        assertEquals("", result);
    }

    // === Skipped Nodes (Condition branching) ===

    @Test
    void computeSkippedNodes_noConditions() {
        WorkflowSchema s = buildSchema(nodes("a", "b"), edges("a->b"));
        Set<String> skipped = schemaService.computeSkippedNodesPublic(s);
        assertTrue(skipped.isEmpty());
    }

    @Test
    void computeSkippedNodes_conditionTrueSkipsFalseBranch() {
        Node a = node("a", "source");
        Node cond = node("cond", "condition");
        Node b = node("b", "agent");
        Node c = node("c", "agent");

        Edge toCond = edge("a", "cond");
        Edge toB = edge("cond", "b");
        toB.setSourcePort("true");
        Edge toC = edge("cond", "c");
        toC.setSourcePort("false");

        WorkflowSchema s = new WorkflowSchema();
        s.setId("schema-id");
        s.setNodes(List.of(a, cond, b, c));
        s.setEdges(List.of(toCond, toB, toC));

        // Register condition result as true
        schemaService.setConditionResult("schema-id:cond", "true");

        Set<String> skipped = schemaService.computeSkippedNodesPublic(s);
        assertTrue(skipped.contains("c"));
        assertFalse(skipped.contains("b"));
    }

    // === Sleep with Cancel ===

    @Test
    void sleepWithCancel_returnsFalseWhenCancelled() {
        AtomicBoolean cancel = new AtomicBoolean(true);
        assertFalse(schemaService.sleepWithCancelPublic(100, cancel));
    }

    @Test
    void sleepWithCancel_returnsTrueWhenNotCancelled() {
        AtomicBoolean cancel = new AtomicBoolean(false);
        assertTrue(schemaService.sleepWithCancelPublic(50, cancel));
    }

    // === Mermaid Export ===

    @Test
    void exportToMermaid_linearGraph() {
        when(schemaRepository.findById("test")).thenReturn(buildSchema(
            nodes("a", "b", "c"),
            edges("a->b", "b->c")
        ));
        schemaService.setId("test");

        String mermaid = schemaService.exportToMermaid("test");
        assertTrue(mermaid.contains("a[\"a\"]"));
        assertTrue(mermaid.contains("b[\"b\"]"));
        assertTrue(mermaid.contains("a --> b"));
        assertTrue(mermaid.contains("b --> c"));
    }

    @Test
    void exportToMermaid_nullSchema() {
        when(schemaRepository.findById("empty")).thenReturn(null);
        String mermaid = schemaService.exportToMermaid("empty");
        assertEquals("", mermaid);
    }

    // === CRUD ===

    @Test
    void createSchema_generatesId() {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setName("Test");
        schemaService.createSchema(schema);
        assertNotNull(schema.getId());
        verify(schemaRepository).save(any(WorkflowSchema.class));
    }

    @Test
    void deleteSchema_callsRepository() {
        schemaService.deleteSchema("test-id");
        verify(schemaRepository).delete("test-id");
    }

    // === Output Node File Writing ===

    @Test
    void outputNode_logType_returnsPredecessorResult() {
        String result = schemaService.writeOutputPublic("log", null, "text", "hello from agent");
        assertEquals("hello from agent", result);
    }

    @Test
    void outputNode_fileType_writesToDisk() throws Exception {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("axolotl-test");
        java.nio.file.Path filePath = tempDir.resolve("output.md");
        try {
            schemaService.writeOutputPublic("file", filePath.toString(), "markdown", "result content");
            String fileContent = java.nio.file.Files.readString(filePath);
            assertEquals("result content", fileContent);
        } finally {
            java.nio.file.Files.deleteIfExists(filePath);
            java.nio.file.Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void outputNode_fileType_createsParentDirs() throws Exception {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("axolotl-test");
        java.nio.file.Path filePath = tempDir.resolve("sub/dir/output.txt");
        try {
            schemaService.writeOutputPublic("file", filePath.toString(), "text", "nested content");
            assertTrue(java.nio.file.Files.exists(filePath));
            assertEquals("nested content", java.nio.file.Files.readString(filePath));
        } finally {
            // Recursive delete of temp dir
            java.nio.file.Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
    }

    @Test
    void outputNode_fileType_json_wrapsResult() throws Exception {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("axolotl-test");
        java.nio.file.Path filePath = tempDir.resolve("output.json");
        try {
            schemaService.writeOutputPublic("file", filePath.toString(), "json", "some text");
            String fileContent = java.nio.file.Files.readString(filePath);
            assertTrue(fileContent.contains("\"result\""));
            assertTrue(fileContent.contains("some text"));
        } finally {
            java.nio.file.Files.deleteIfExists(filePath);
            java.nio.file.Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void outputNode_nullType_defaultsToLog() {
        String result = schemaService.writeOutputPublic(null, null, null, "default behavior");
        assertEquals("default behavior", result);
    }

    @Test
    void outputNode_nullContent_defaultsToNoData() {
        String result = schemaService.writeOutputPublic("log", null, "text", null);
        assertEquals("Нет данных для вывода", result);
    }

    // === Helpers ===

    private WorkflowSchema schema;

    private WorkflowSchema buildSchema(List<Node> nodes, List<Edge> edges) {
        schema = new WorkflowSchema();
        schema.setId("schema-id");
        schema.setName("Test Schema");
        schema.setNodes(nodes);
        schema.setEdges(edges);
        return schema;
    }

    private List<Node> nodes(String... ids) {
        return Arrays.stream(ids).map(id -> {
            Node n = new Node();
            n.setId(id);
            n.setName(id);
            n.setType("agent");
            n.setPosition(new Node.Position());
            n.setData(new Node.NodeData());
            return n;
        }).toList();
    }

    private List<Edge> edges(String... specs) {
        return Arrays.stream(specs).map(spec -> {
            String[] parts = spec.split("->");
            return edge(parts[0], parts[1]);
        }).toList();
    }

    private Node node(String id, String type) {
        Node n = new Node();
        n.setId(id);
        n.setName(id);
        n.setType(type);
        n.setPosition(new Node.Position());
        n.setData(new Node.NodeData());
        return n;
    }

    private Edge edge(String source, String target) {
        Edge e = new Edge();
        e.setId("edge-" + source + "-" + target);
        e.setSource(source);
        e.setTarget(target);
        e.setType("data");
        return e;
    }

    private List<String> names(List<Node> nodes) {
        return nodes.stream().map(Node::getName).toList();
    }
}
