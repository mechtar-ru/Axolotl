package com.agent.orchestrator.service;

import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.model.*;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SchemaServiceTest {

    @Mock Neo4jSchemaRepository schemaRepository;
    @Mock ExecutionWebSocketHandler webSocketHandler;
    @Mock MemPalaceClient memPalaceClient;
    @Mock SettingsService settingsService;
    @Mock MetricsService metricsService;
    @Mock NodeExecutor nodeExecutor;
    @Mock SchemaExporter schemaExporter;
    @Mock LlmService llmService;
    @Mock PlanService planService;

    SchemaService schemaService;

    @BeforeEach
    void setUp() {
        schemaService = new SchemaService(schemaRepository, webSocketHandler, memPalaceClient,
                settingsService, metricsService, nodeExecutor, schemaExporter, llmService, planService);
    }

    // === Topological Sort / Execution Levels ===

    @Test
    void getExecutionLevels_linearGraph() {
        WorkflowSchema schema = buildSchema(nodes("a", "b", "c"), edges("a->b", "b->c"));
        var levels = schemaService.getExecutionLevelsPublic(schema);
        assertEquals(3, levels.size());
        assertEquals(List.of("a"), names(levels.get(0)));
        assertEquals(List.of("b"), names(levels.get(1)));
        assertEquals(List.of("c"), names(levels.get(2)));
    }

    @Test
    void getExecutionLevels_diamondGraph() {
        WorkflowSchema schema = buildSchema(nodes("a", "b", "c", "d"),
                edges("a->b", "a->c", "b->d", "c->d"));
        var levels = schemaService.getExecutionLevelsPublic(schema);
        assertEquals(3, levels.size());
        assertEquals(List.of("a"), names(levels.get(0)));
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
        WorkflowSchema schema = buildSchema(nodes("a", "b", "c"), edges());
        var levels = schemaService.getExecutionLevelsPublic(schema);
        assertEquals(1, levels.size());
        assertEquals(3, levels.get(0).size());
    }

    // === Skipped Nodes (Condition branching) ===

    @Test
    void computeSkippedNodes_noConditions() {
        WorkflowSchema s = buildSchema(nodes("a", "b"), edges("a->b"));
        Map<String, String> condResults = new HashMap<>();
        when(nodeExecutor.getConditionResults()).thenReturn(condResults);
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

        Map<String, String> condResults = new HashMap<>();
        condResults.put("schema-id:cond", "true");
        when(nodeExecutor.getConditionResults()).thenReturn(condResults);

        Set<String> skipped = schemaService.computeSkippedNodesPublic(s);
        assertTrue(skipped.contains("c"));
        assertFalse(skipped.contains("b"));
    }

    // === Export delegation ===

    @Test
    void exportToMermaid_delegatesToExporter() {
        when(schemaExporter.exportToMermaid("test")).thenReturn("mermaid-output");
        assertEquals("mermaid-output", schemaService.exportToMermaid("test"));
        verify(schemaExporter).exportToMermaid("test");
    }

    @Test
    void exportToPython_delegatesToExporter() {
        when(schemaExporter.exportToPython("test")).thenReturn("python-output");
        assertEquals("python-output", schemaService.exportToPython("test"));
        verify(schemaExporter).exportToPython("test");
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

    @Test
    void getOutputFileContent_delegatesToNodeExecutor() {
        Map<String, String> registry = new HashMap<>();
        registry.put("s1:n1", "/tmp/out.txt");
        when(nodeExecutor.getOutputFileRegistry()).thenReturn(registry);
        // Returns null because file doesn't exist, but delegation is verified
        schemaService.getOutputFileContent("s1", "n1");
        verify(nodeExecutor).getOutputFileRegistry();
    }

    // === Helpers ===

    private WorkflowSchema buildSchema(List<Node> nodes, List<Edge> edges) {
        WorkflowSchema schema = new WorkflowSchema();
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

    @Test
    void getExecutionResults_returnsEmptyMapForUnknownExecution() {
        // nodeExecutor.getNodeResults() returns an empty ConcurrentHashMap by default
        when(nodeExecutor.getNodeResults()).thenReturn(new java.util.concurrent.ConcurrentHashMap<>());
        
        Map<String, String> results = schemaService.getExecutionResults("unknown-exec");
        assertTrue(results.isEmpty());
    }

    @Test
    void generateNodes_returnsSuccessWithSchema() {
        String schemaId = "test-schema-id";
        String prompt = "Create a calculator app";
        WorkflowSchema existingSchema = new WorkflowSchema();
        existingSchema.setId(schemaId);
        existingSchema.setName("Test App");
        existingSchema.setNodes(new ArrayList<>());
        existingSchema.setEdges(new ArrayList<>());

        when(schemaRepository.findById(schemaId)).thenReturn(existingSchema);
        when(settingsService.getGlobalDefaultModel()).thenReturn("test-model");
        when(llmService.chat(anyString(), anyString(), eq(prompt), isNull()))
            .thenReturn("{\"nodes\":[{\"id\":\"n1\",\"type\":\"agent\",\"name\":\"Calc Agent\"}],\"edges\":[]}");

        Map<String, Object> result = schemaService.generateNodes(schemaId, prompt, null);

        assertTrue((Boolean) result.get("success"));
        assertNotNull(result.get("schema"));
        verify(schemaRepository).save(any(WorkflowSchema.class));
    }

    @Test
    void generateNodes_returnsErrorWhenSchemaNotFound() {
        String schemaId = "nonexistent";
        when(schemaRepository.findById(schemaId)).thenReturn(null);

        Map<String, Object> result = schemaService.generateNodes(schemaId, "prompt", "model");

        assertFalse((Boolean) result.get("success"));
        assertEquals("Schema not found: nonexistent", result.get("error"));
    }

    @Test
    void generateNodes_returnsErrorWhenEmptyResponse() {
        String schemaId = "test-id";
        WorkflowSchema existingSchema = new WorkflowSchema();
        existingSchema.setId(schemaId);
        when(schemaRepository.findById(schemaId)).thenReturn(existingSchema);
        when(settingsService.getGlobalDefaultModel()).thenReturn("test-model");
        when(llmService.chat(anyString(), anyString(), anyString(), isNull()))
            .thenReturn("");

        Map<String, Object> result = schemaService.generateNodes(schemaId, "prompt", null);

        assertFalse((Boolean) result.get("success"));
        assertEquals("LLM returned empty response", result.get("error"));
    }

    @Test
    void generateNodes_returnsErrorWhenNoNodes() {
        String schemaId = "test-id";
        WorkflowSchema existingSchema = new WorkflowSchema();
        existingSchema.setId(schemaId);
        when(schemaRepository.findById(schemaId)).thenReturn(existingSchema);
        when(settingsService.getGlobalDefaultModel()).thenReturn("test-model");
        when(llmService.chat(anyString(), anyString(), anyString(), isNull()))
            .thenReturn("{\"nodes\":[],\"edges\":[]}");

        Map<String, Object> result = schemaService.generateNodes(schemaId, "prompt", null);

        assertFalse((Boolean) result.get("success"));
        assertEquals("No nodes generated. Try a more specific description.", result.get("error"));
    }

    @Test
    void generateNodes_returnsErrorOnInvalidJson() {
        String schemaId = "test-id";
        WorkflowSchema existingSchema = new WorkflowSchema();
        existingSchema.setId(schemaId);
        when(schemaRepository.findById(schemaId)).thenReturn(existingSchema);
        when(settingsService.getGlobalDefaultModel()).thenReturn("test-model");
        when(llmService.chat(anyString(), anyString(), anyString(), isNull()))
            .thenReturn("not json at all");

        Map<String, Object> result = schemaService.generateNodes(schemaId, "prompt", null);

        assertFalse((Boolean) result.get("success"));
        assertTrue(((String) result.get("error")).startsWith("Failed to parse"));
    }
}
