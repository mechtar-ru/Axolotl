package com.agent.orchestrator.integration;

import com.agent.orchestrator.config.DbConfig;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.model.ExecutionRun;
import com.agent.orchestrator.model.NodeExecution;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.service.NodeExecutor;
import com.agent.orchestrator.service.SchemaService;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionResumeIntegrationTest {

    @Mock Neo4jSchemaRepository schemaRepository;
    @Mock ExecutionWebSocketHandler webSocketHandler;
    @Mock MemPalaceClient memPalaceClient;
    @Mock com.agent.orchestrator.service.SettingsService settingsService;
    @Mock com.agent.orchestrator.service.MetricsService metricsService;

    private ExecutionRepository executionRepository;
    private SchemaService schemaService;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        DbConfig config = new DbConfig(tempDir.resolve("test.db").toString());
        executionRepository = new ExecutionRepository(config);

        // Minimal collaborators — nodeExecutor is mocked to avoid heavy execution
        NodeExecutor nodeExecutor = mock(NodeExecutor.class);
        schemaService = new SchemaService(schemaRepository, webSocketHandler, memPalaceClient,
                settingsService, metricsService, nodeExecutor, null, null, null, executionRepository);
        lenient().when(settingsService.getGlobalDefaultModel()).thenReturn("ollama");
    }

    @Test
    void resumeExecution_createsChildRun_and_skipsUnchangedNodes() {
        // Prepare schema with 3 nodes
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("schema-resume");
        Node n1 = new Node(); n1.setId("n1"); n1.setName("A"); n1.setType("source");
        Node n2 = new Node(); n2.setId("n2"); n2.setName("B"); n2.setType("agent");
        Node n3 = new Node(); n3.setId("n3"); n3.setName("C"); n3.setType("output");
        schema.setNodes(List.of(n1,n2,n3));

        // Create parent run paused with node executions: n1 completed, n2 failed, n3 pending
        ExecutionRun parent = new ExecutionRun();
        parent.setId(UUID.randomUUID().toString());
        parent.setSchemaId(schema.getId());
        parent.setStatus("paused");
        parent.setMode("EXECUTE");
        parent.setStartedAt(Instant.now().toString());
        executionRepository.createRun(parent);

        NodeExecution ne1 = new NodeExecution(); ne1.setId(UUID.randomUUID().toString()); ne1.setRunId(parent.getId()); ne1.setNodeId("n1"); ne1.setStatus("completed"); ne1.setConfigHash(schemaService.computeConfigHash(n1, schema)); ne1.setOutputSummary("resultA"); executionRepository.createNodeExecution(ne1);
        NodeExecution ne2 = new NodeExecution(); ne2.setId(UUID.randomUUID().toString()); ne2.setRunId(parent.getId()); ne2.setNodeId("n2"); ne2.setStatus("failed"); ne2.setConfigHash(schemaService.computeConfigHash(n2, schema)); executionRepository.createNodeExecution(ne2);
        NodeExecution ne3 = new NodeExecution(); ne3.setId(UUID.randomUUID().toString()); ne3.setRunId(parent.getId()); ne3.setNodeId("n3"); ne3.setStatus("pending"); ne3.setConfigHash(schemaService.computeConfigHash(n3, schema)); executionRepository.createNodeExecution(ne3);

        // Call resumeExecution
        lenient().when(schemaRepository.findById(schema.getId())).thenReturn(schema);
        schemaService.resumeExecution(schema.getId(), schema);

        // Verify child run created
        List<ExecutionRun> runs = executionRepository.getRunsBySchema(schema.getId());
        assertEquals(2, runs.size(), "Parent and child runs should exist");
        ExecutionRun child = runs.stream().filter(r -> !r.getId().equals(parent.getId())).findFirst().orElse(null);
        assertNotNull(child);
        assertEquals("running", child.getStatus());

        // Child node executions should exist and n1 should be skipped
        List<NodeExecution> childExecs = executionRepository.getNodeExecutionsByRun(child.getId());
        assertEquals(3, childExecs.size());
        NodeExecution childNe1 = childExecs.stream().filter(e -> e.getNodeId().equals("n1")).findFirst().orElse(null);
        assertNotNull(childNe1); assertEquals("skipped", childNe1.getStatus());
        NodeExecution childNe2 = childExecs.stream().filter(e -> e.getNodeId().equals("n2")).findFirst().orElse(null);
        assertNotNull(childNe2); assertEquals("pending", childNe2.getStatus());
    }
}
