package com.agent.orchestrator.service;

import com.agent.orchestrator.config.DbConfig;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.model.*;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionResilienceFlowIntegrationTest {

    @TempDir
    Path tempDir;

    private ExecutionRepository executionRepository;
    private SchemaService schemaService;

    @Mock Neo4jSchemaRepository schemaRepository;
    @Mock ExecutionWebSocketHandler webSocketHandler;
    @Mock MemPalaceClient memPalaceClient;
    @Mock SettingsService settingsService;
    @Mock MetricsService metricsService;
    @Mock NodeExecutor nodeExecutor;
    @Mock SchemaExporter schemaExporter;
    @Mock LlmService llmService;

    @BeforeEach
    void setUp() {
        String dbPath = tempDir.resolve("flow-test.db").toString();
        DbConfig config = new DbConfig(dbPath);
        executionRepository = new ExecutionRepository(config);
        PlanService planService = mock(PlanService.class);

        schemaService = new SchemaService(schemaRepository, webSocketHandler, memPalaceClient,
                settingsService, metricsService, nodeExecutor, schemaExporter, llmService,
                planService, executionRepository);
    }

    @Test
    void resumeFlow_createChildRunWithSkippedNodes() throws Exception {
        String schemaId = "resume-flow-schema";
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId(schemaId);
        schema.setName("Resume Test");

        Node n1 = new Node(); n1.setId("n1"); n1.setName("Source"); n1.setType("source");
        Node n2 = new Node(); n2.setId("n2"); n2.setName("Agent"); n2.setType("agent");
        Node n3 = new Node(); n3.setId("n3"); n3.setName("Output"); n3.setType("output");

        Node.NodeData data2 = new Node.NodeData();
        data2.setModel("gpt-4o");
        data2.setUserPrompt("Analyze");
        n2.setData(data2);

        schema.setNodes(List.of(n1, n2, n3));
        Edge e1 = new Edge(); e1.setId("e1"); e1.setSource("n1"); e1.setTarget("n2");
        Edge e2 = new Edge(); e2.setId("e2"); e2.setSource("n2"); e2.setTarget("n3");
        schema.setEdges(List.of(e1, e2));

        when(schemaRepository.findById(schemaId)).thenReturn(schema);

        String runId = UUID.randomUUID().toString();
        ExecutionRun pausedRun = new ExecutionRun();
        pausedRun.setId(runId);
        pausedRun.setSchemaId(schemaId);
        pausedRun.setStatus("paused");
        pausedRun.setMode("EXECUTE");
        pausedRun.setStartedAt(java.time.Instant.now().toString());
        pausedRun.setError("Token limit");
        executionRepository.createRun(pausedRun);

        String hash1 = schemaService.computeConfigHash(n1, schema);
        NodeExecution ne1 = new NodeExecution();
        ne1.setId(UUID.randomUUID().toString());
        ne1.setRunId(runId);
        ne1.setNodeId("n1");
        ne1.setNodeName("Source");
        ne1.setNodeType("source");
        ne1.setStatus("completed");
        ne1.setConfigHash(hash1);
        ne1.setOutputSummary("{\"data\": \"source data\"}");
        ne1.setStartedAt(java.time.Instant.now().toString());
        ne1.setCompletedAt(java.time.Instant.now().toString());
        executionRepository.createNodeExecution(ne1);

        String hash2 = schemaService.computeConfigHash(n2, schema);
        NodeExecution ne2 = new NodeExecution();
        ne2.setId(UUID.randomUUID().toString());
        ne2.setRunId(runId);
        ne2.setNodeId("n2");
        ne2.setNodeName("Agent");
        ne2.setNodeType("agent");
        ne2.setStatus("failed");
        ne2.setConfigHash(hash2);
        ne2.setError("HTTP 429 rate limit");
        ne2.setStartedAt(java.time.Instant.now().toString());
        executionRepository.createNodeExecution(ne2);

        NodeExecution ne3 = new NodeExecution();
        ne3.setId(UUID.randomUUID().toString());
        ne3.setRunId(runId);
        ne3.setNodeId("n3");
        ne3.setNodeName("Output");
        ne3.setNodeType("output");
        ne3.setStatus("pending");
        ne3.setConfigHash(hash2);
        ne3.setStartedAt(java.time.Instant.now().toString());
        executionRepository.createNodeExecution(ne3);

        when(nodeExecutor.getNodeResults()).thenReturn(new ConcurrentHashMap<>());
        schemaService.resumeExecution(schemaId);

        List<ExecutionRun> runs = executionRepository.getRunsBySchema(schemaId);
        assertEquals(2, runs.size(), "Should have parent + child run");

        ExecutionRun childRun = runs.stream()
                .filter(r -> r.getResumesFrom() != null)
                .findFirst().orElse(null);
        assertNotNull(childRun, "Child run should reference parent");
        assertEquals(runId, childRun.getResumesFrom());
        assertEquals("running", childRun.getStatus());

        List<NodeExecution> childNodes = executionRepository.getNodeExecutionsByRun(childRun.getId());
        assertEquals(3, childNodes.size());

        NodeExecution childN1 = childNodes.stream().filter(n -> "n1".equals(n.getNodeId())).findFirst().orElse(null);
        NodeExecution childN2 = childNodes.stream().filter(n -> "n2".equals(n.getNodeId())).findFirst().orElse(null);
        NodeExecution childN3 = childNodes.stream().filter(n -> "n3".equals(n.getNodeId())).findFirst().orElse(null);

        assertNotNull(childN1);
        assertEquals("skipped", childN1.getStatus(), "N1 should be skipped (same config)");
        assertNotNull(childN2);
        assertEquals("pending", childN2.getStatus(), "N2 should be pending (was failed)");
        assertNotNull(childN3);
        assertEquals("pending", childN3.getStatus(), "N3 should be pending");
    }

    @Test
    void resumeFlow_configChanged_reExecutesNode() {
        String schemaId = "resume-config-change";
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId(schemaId);
        schema.setName("Config Change Test");

        Node n1 = new Node(); n1.setId("n1"); n1.setName("Agent"); n1.setType("agent");
        Node.NodeData data1 = new Node.NodeData();
        data1.setModel("claude-3");
        data1.setUserPrompt("Old prompt");
        n1.setData(data1);
        schema.setNodes(List.of(n1));
        schema.setEdges(List.of());

        when(schemaRepository.findById(schemaId)).thenReturn(schema);

        String runId = UUID.randomUUID().toString();
        ExecutionRun pausedRun = new ExecutionRun();
        pausedRun.setId(runId);
        pausedRun.setSchemaId(schemaId);
        pausedRun.setStatus("paused");
        pausedRun.setMode("EXECUTE");
        pausedRun.setStartedAt(java.time.Instant.now().toString());
        pausedRun.setError("Rate limit");
        executionRepository.createRun(pausedRun);

        String oldHash = "old-hash-different-config";
        NodeExecution ne1 = new NodeExecution();
        ne1.setId(UUID.randomUUID().toString());
        ne1.setRunId(runId);
        ne1.setNodeId("n1");
        ne1.setNodeName("Agent");
        ne1.setNodeType("agent");
        ne1.setStatus("completed");
        ne1.setConfigHash(oldHash);
        ne1.setOutputSummary("Old result");
        ne1.setStartedAt(java.time.Instant.now().toString());
        ne1.setCompletedAt(java.time.Instant.now().toString());
        executionRepository.createNodeExecution(ne1);

        when(nodeExecutor.getNodeResults()).thenReturn(new ConcurrentHashMap<>());
        schemaService.resumeExecution(schemaId);

        List<ExecutionRun> runs = executionRepository.getRunsBySchema(schemaId);
        assertEquals(2, runs.size());

        ExecutionRun childRun = runs.stream()
                .filter(r -> r.getResumesFrom() != null)
                .findFirst().orElse(null);
        assertNotNull(childRun);

        List<NodeExecution> childNodes = executionRepository.getNodeExecutionsByRun(childRun.getId());
        NodeExecution childN1 = childNodes.stream().filter(n -> "n1".equals(n.getNodeId())).findFirst().orElse(null);
        assertNotNull(childN1);
        assertNotEquals("skipped", childN1.getStatus(),
                "Node with changed config should NOT be skipped");
    }
}
