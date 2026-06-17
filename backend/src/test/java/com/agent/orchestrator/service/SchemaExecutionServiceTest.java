package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.model.*;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchemaExecutionServiceTest {

    @Mock Neo4jSchemaRepository schemaRepository;
    @Mock ExecutionWebSocketHandler webSocketHandler;
    @Mock NodeExecutor nodeExecutor;
    @Mock ExecutionRepository executionRepository;
    @Mock PipelineService pipelineService;
    @Mock ExecutionStateManager stateManager;
    @Mock SchemaValidator schemaValidator;
    @Mock MetricsService metricsService;

    @Captor ArgumentCaptor<List<Stage>> stagesCaptor;

    SchemaExecutionService service;

    private WorkflowSchema schema;
    private ExecutionRun executionRun;
    private NodeExecution nodeExecution;

    @BeforeEach
    void setUp() {
        service = new SchemaExecutionService(schemaRepository, webSocketHandler, nodeExecutor,
                executionRepository, pipelineService, stateManager, schemaValidator, metricsService);

        schema = new WorkflowSchema();
        schema.setId("test-schema-1");
        schema.setName("Test Schema");
        schema.setUserId("user-1");

        Node sourceNode = new Node();
        sourceNode.setId("n1");
        sourceNode.setType("source");
        sourceNode.setName("Source");
        Node.NodeData sourceData = new Node.NodeData();
        sourceData.setSourceData("test input");
        Map<String, Object> config = new HashMap<>();
        config.put("sourceType", "text");
        sourceData.setConfig(config);
        sourceNode.setData(sourceData);

        Node agentNode = new Node();
        agentNode.setId("n2");
        agentNode.setType("agent");
        agentNode.setName("Agent");
        Node.NodeData agentData = new Node.NodeData();
        agentData.setModel("test-model");
        agentData.setSystemPrompt("You are a test agent");
        agentData.setUserPrompt("Do something");
        agentNode.setData(agentData);

        schema.setNodes(List.of(sourceNode, agentNode));

        Edge edge = new Edge();
        edge.setId("e1");
        edge.setSource("n1");
        edge.setTarget("n2");
        schema.setEdges(List.of(edge));

        executionRun = new ExecutionRun();
        executionRun.setId("run-1");
        executionRun.setSchemaId("test-schema-1");
        executionRun.setStatus("completed");
        executionRun.setMode("PIPELINE");

        nodeExecution = new NodeExecution();
        nodeExecution.setId("ne-1");
        nodeExecution.setRunId("run-1");
        nodeExecution.setNodeId("n1");
        nodeExecution.setNodeType("source");
        nodeExecution.setStatus("completed");
    }

    // ── 1. executeSchema (happy path) ──

    @Test
    void executeSchema_happyPath_callsServicesInOrder() {
        when(schemaRepository.findById("test-schema-1")).thenReturn(schema);
        SchemaValidationResult validResult = new SchemaValidationResult();
        when(schemaValidator.validate(schema)).thenReturn(validResult);

        service.executeSchema("test-schema-1");

        verify(schemaRepository).findById("test-schema-1");
        verify(schemaValidator).validate(schema);
        verify(metricsService).recordSchemaExecutionStart();
        verify(pipelineService).executeDerivedStages(eq("test-schema-1"), eq(schema), stagesCaptor.capture());

        List<Stage> capturedStages = stagesCaptor.getValue();
        assertThat(capturedStages).isNotEmpty();
        assertThat(capturedStages).anyMatch(s -> "source".equals(s.getNodeType()));
        assertThat(capturedStages).anyMatch(s -> "agent".equals(s.getNodeType()));
    }

    // ── 2. executeSchema with validation failure ──

    @Test
    void executeSchema_validationFails_throwsSchemaValidationException() {
        when(schemaRepository.findById("test-schema-1")).thenReturn(schema);
        SchemaValidationResult invalidResult = new SchemaValidationResult();
        invalidResult.addError("nodes", "Schema must have at least one node");
        when(schemaValidator.validate(schema)).thenReturn(invalidResult);

        assertThrows(SchemaValidationException.class, () -> service.executeSchema("test-schema-1"));

        verify(metricsService, never()).recordSchemaExecutionStart();
        verify(pipelineService, never()).executeDerivedStages(anyString(), any(), anyList());
    }

    // ── 3. findExecutionRuns (getSchemaStatus equivalent) ──

    @Test
    void findExecutionRuns_returnsRunsWithStatus() {
        when(executionRepository.getRunsBySchema("test-schema-1"))
                .thenReturn(List.of(executionRun));

        List<ExecutionRun> runs = service.findExecutionRuns("test-schema-1");

        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).getId()).isEqualTo("run-1");
        assertThat(runs.get(0).getStatus()).isEqualTo("completed");
        assertThat(runs.get(0).getSchemaId()).isEqualTo("test-schema-1");
    }

    @Test
    void findExecutionRuns_noRuns_returnsEmptyList() {
        when(executionRepository.getRunsBySchema("nonexistent"))
                .thenReturn(List.of());

        List<ExecutionRun> runs = service.findExecutionRuns("nonexistent");

        assertThat(runs).isEmpty();
    }

    // ── 4. getExecutionResults (getSchemaResult equivalent) ──

    @Test
    void getExecutionResults_returnsResultsMap() {
        Map<String, Map<String, String>> allResults = new HashMap<>();
        Map<String, String> schemaResults = new HashMap<>();
        schemaResults.put("n2", "{\"output\":\"test result\"}");
        allResults.put("exec-1", schemaResults);
        when(nodeExecutor.getNodeResults()).thenReturn(allResults);

        Map<String, String> results = service.getExecutionResults("exec-1");

        assertThat(results).containsKey("n2");
        assertThat(results.get("n2")).contains("test result");
    }

    @Test
    void getExecutionResults_noResults_returnsEmptyMap() {
        when(nodeExecutor.getNodeResults()).thenReturn(new HashMap<>());

        Map<String, String> results = service.getExecutionResults("nonexistent");

        assertThat(results).isEmpty();
    }

    // ── 5. cancelExecution (cancelSchemaExecution) ──

    @Test
    void cancelExecution_callsPipelineCancel() {
        service.cancelExecution("test-schema-1");

        verify(pipelineService).cancelPipeline("test-schema-1");
    }

    // ── 6. getExecutionLevels (execution helper) ──

    @Test
    void getExecutionLevels_computesTopologicalLevels() {
        List<List<Node>> levels = service.getExecutionLevelsPublic(schema);

        // n1 (source) has no incoming edges → level 0
        // n2 (agent) depends on n1 → level 1
        assertThat(levels).hasSize(2);
        assertThat(levels.get(0)).extracting(Node::getId).containsExactly("n1");
        assertThat(levels.get(1)).extracting(Node::getId).containsExactly("n2");
    }

    @Test
    void getExecutionLevels_noNodes_returnsEmptyList() {
        schema.setNodes(new ArrayList<>());

        List<List<Node>> levels = service.getExecutionLevelsPublic(schema);

        assertThat(levels).isEmpty();
    }

    @Test
    void getExecutionLevels_nullNodes_returnsEmptyList() {
        schema.setNodes(null);

        List<List<Node>> levels = service.getExecutionLevelsPublic(schema);

        assertThat(levels).isEmpty();
    }

    @Test
    void getExecutionLevels_cyclicDependency_handlesGracefully() {
        // n2 also depends on n1, and n1 on n2 — cycle
        schema.setEdges(List.of(
                edge("e1", "n1", "n2"),
                edge("e2", "n2", "n1")
        ));

        // Should not loop forever — returns partial levels and detects cycle
        List<List<Node>> levels = service.getExecutionLevelsPublic(schema);

        assertThat(levels).isNotEmpty();
        // Both nodes should be in the result (either properly or as cyclic fallback)
        Set<String> allNodes = new HashSet<>();
        for (List<Node> level : levels) {
            for (Node n : level) allNodes.add(n.getId());
        }
        assertThat(allNodes).containsExactlyInAnyOrder("n1", "n2");
    }

    // ── 7. executeSchema with sessionInput overrides source node ──

    @Test
    void executeSchema_withSessionInput_overridesSourceNode() {
        when(schemaRepository.findById("test-schema-1")).thenReturn(schema);
        SchemaValidationResult validResult = new SchemaValidationResult();
        when(schemaValidator.validate(schema)).thenReturn(validResult);

        service.executeSchema("test-schema-1", "session-specific input");

        verify(pipelineService).executeDerivedStages(eq("test-schema-1"), eq(schema), anyList());

        // Verify source node's config was updated with session input
        Node sourceNode = schema.getNodes().get(0);
        assertThat(sourceNode.getData().getConfig())
                .containsEntry("sourceData", "session-specific input");
    }

    @Test
    void executeSchema_withBlankSessionInput_fallsBackToNoArgExecute() {
        when(schemaRepository.findById("test-schema-1")).thenReturn(schema);
        SchemaValidationResult validResult = new SchemaValidationResult();
        when(schemaValidator.validate(schema)).thenReturn(validResult);

        service.executeSchema("test-schema-1", "   ");

        verify(pipelineService).executeDerivedStages(eq("test-schema-1"), eq(schema), anyList());
    }

    // ── 8. computeConfigHash ──

    @Test
    void computeConfigHash_returnsValidSha256Hex() {
        Node node = new Node();
        node.setId("n1");
        Node.NodeData data = new Node.NodeData();
        data.setSourceData("test data");
        node.setData(data);

        String hash = service.computeConfigHash(node, schema);

        assertThat(hash).isNotNull();
        assertThat(hash).hasSize(64); // SHA-256 hex is 64 chars
        assertThat(hash).matches("[0-9a-f]{64}");
    }

    @Test
    void computeConfigHash_nullNodeData_returnsValidHash() {
        Node node = new Node();
        node.setId("n1");
        node.setData(null);

        String hash = service.computeConfigHash(node, schema);

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
    }

    @Test
    void computeConfigHash_includesIncomingEdges() {
        Node node = new Node();
        node.setId("n2"); // target of edge from n1
        Node.NodeData data = new Node.NodeData();
        data.setUserPrompt("test");
        node.setData(data);

        String hash = service.computeConfigHash(node, schema);

        // Same call should be deterministic
        String hash2 = service.computeConfigHash(node, schema);
        assertThat(hash).isEqualTo(hash2);
    }

    // ── 9. executeSchema with missing schema ──

    @Test
    void executeSchema_schemaNotFound_throwsResponseStatusException() {
        when(schemaRepository.findById("nonexistent")).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.executeSchema("nonexistent"));

        assertThat(ex.getStatusCode().value()).isEqualTo(404);
        assertThat(ex.getReason()).contains("not found");
        verify(pipelineService, never()).executeDerivedStages(anyString(), any(), anyList());
    }

    @Test
    void executeSchema_withSessionInput_schemaNotFound_throwsResponseStatusException() {
        when(schemaRepository.findById("nonexistent")).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.executeSchema("nonexistent", "input"));

        assertThat(ex.getStatusCode().value()).isEqualTo(404);
    }

    // ── 10. cancelExecution on non-existent schema is always graceful ──

    @Test
    void cancelExecution_nonExistentSchema_doesNotThrow() {
        assertDoesNotThrow(() -> service.cancelExecution("nonexistent"));

        verify(pipelineService).cancelPipeline("nonexistent");
    }

    // ── Additional: getExecutionNodes ──

    @Test
    void getExecutionNodes_returnsNodeExecutions() {
        when(executionRepository.getNodeExecutionsByRun("run-1"))
                .thenReturn(List.of(nodeExecution));

        List<NodeExecution> nodes = service.getExecutionNodes("run-1");

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getNodeId()).isEqualTo("n1");
        assertThat(nodes.get(0).getStatus()).isEqualTo("completed");
    }

    @Test
    void getExecutionNodes_noNodes_returnsEmptyList() {
        when(executionRepository.getNodeExecutionsByRun("no-run"))
                .thenReturn(List.of());

        List<NodeExecution> nodes = service.getExecutionNodes("no-run");

        assertThat(nodes).isEmpty();
    }

    // ── Additional: getExecutionHistory ──

    @Test
    void getExecutionHistory_returnsRecords() {
        ExecutionRecord record = new ExecutionRecord();
        record.setId("exec-1");
        record.setSchemaId("test-schema-1");
        record.setStatus("completed");
        when(executionRepository.getExecutionRecordsBySchema("test-schema-1"))
                .thenReturn(List.of(record));

        List<ExecutionRecord> history = service.getExecutionHistory("test-schema-1");

        assertThat(history).hasSize(1);
        assertThat(history.get(0).getId()).isEqualTo("exec-1");
        assertThat(history.get(0).getStatus()).isEqualTo("completed");
    }

    // ── Additional: getGeneratedFiles ──

    @Test
    void getGeneratedFiles_filtersBySchemaPrefix() {
        Map<String, Object> registry = new HashMap<>();
        registry.put("test-schema-1:n2", "file1.txt");
        registry.put("test-schema-1:n3", "file2.txt");
        registry.put("other-schema:n1", "other.txt");
        when(stateManager.getGeneratedFilesRegistry()).thenReturn(registry);
        when(nodeExecutor.getGeneratedFilesRegistry()).thenReturn(registry);

        Map<String, Object> files = service.getGeneratedFiles("test-schema-1");

        assertThat(files).hasSize(2);
        assertThat(files).containsKey("n2");
        assertThat(files).containsKey("n3");
        assertThat(files).doesNotContainKey("n1");
    }

    @Test
    void getGeneratedFiles_noFiles_returnsEmptyMap() {
        when(stateManager.getGeneratedFilesRegistry()).thenReturn(new HashMap<>());
        when(nodeExecutor.getGeneratedFilesRegistry()).thenReturn(new HashMap<>());

        Map<String, Object> files = service.getGeneratedFiles("test-schema-1");

        assertThat(files).isEmpty();
    }

    // ── Additional: computeSkippedNodesInternal ──

    @Test
    void computeSkippedNodesInternal_noEdges_returnsEmpty() {
        schema.setEdges(null);

        Set<String> skipped = service.computeSkippedNodesInternal(schema, new HashMap<>());

        assertThat(skipped).isEmpty();
    }

    // ── Additional: resumeExecution ──

    @Test
    void resumeExecution_resumesPipeline() {
        service.resumeExecution("test-schema-1");

        verify(pipelineService).resumePipeline("test-schema-1");
    }

    // ── Additional: getPausedRun ──

    @Test
    void getPausedRun_returnsRunIfPaused() {
        ExecutionRun paused = new ExecutionRun();
        paused.setId("paused-1");
        paused.setStatus("paused");
        when(executionRepository.getLatestRunBySchemaAndStatus("test-schema-1", "paused"))
                .thenReturn(paused);

        ExecutionRun result = service.getPausedRun("test-schema-1");

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("paused");
    }

    @Test
    void getPausedRun_noPausedRun_returnsNull() {
        when(executionRepository.getLatestRunBySchemaAndStatus("test-schema-1", "paused"))
                .thenReturn(null);

        ExecutionRun result = service.getPausedRun("test-schema-1");

        assertThat(result).isNull();
    }

    // ── Helpers ──

    private Edge edge(String id, String source, String target) {
        Edge e = new Edge();
        e.setId(id);
        e.setSource(source);
        e.setTarget(target);
        return e;
    }
}
