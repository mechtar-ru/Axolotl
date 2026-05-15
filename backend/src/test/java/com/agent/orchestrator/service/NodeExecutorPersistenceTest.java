package com.agent.orchestrator.service;

import com.agent.orchestrator.config.DbConfig;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.model.ExecutionRun;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.NodeExecution;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NodeExecutorPersistenceTest {

    @Mock Neo4jSchemaRepository schemaRepository;
    @Mock ExecutionWebSocketHandler webSocketHandler;
    @Mock MemPalaceClient memPalaceClient;
    @Mock LlmService llmService;
    @Mock ToolExecutor toolExecutor;
    @Mock TransformService transformService;
    @Mock PlanService planService;
    @Mock ProjectContextBuilder projectContextBuilder;

    private ExecutionRepository executionRepository;
    private NodeExecutor nodeExecutor;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        String dbPath = tempDir.resolve("test.db").toString();
        DbConfig config = new DbConfig(dbPath);
        executionRepository = new ExecutionRepository(config);

        nodeExecutor = new NodeExecutor(llmService, webSocketHandler, memPalaceClient,
                toolExecutor, transformService, schemaRepository, null, projectContextBuilder, executionRepository);
    }

    @Test
    void executeNode_updatesNodeExecutionToCompleted() {
        // prepare run + node execution placeholder
        ExecutionRun run = new ExecutionRun();
        run.setId(UUID.randomUUID().toString());
        run.setSchemaId("schema-1");
        run.setStatus("running");
        run.setMode("EXECUTE");
        run.setStartedAt(Instant.now().toString());
        executionRepository.createRun(run);

        NodeExecution ne = new NodeExecution();
        ne.setId(UUID.randomUUID().toString());
        ne.setRunId(run.getId());
        ne.setNodeId("n1");
        ne.setNodeName("Source");
        ne.setNodeType("source");
        ne.setStatus("pending");
        ne.setStartedAt(Instant.now().toString());
        executionRepository.createNodeExecution(ne);

        // prepare schema and node
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("schema-1");
        lenient().when(schemaRepository.findById("schema-1")).thenReturn(schema);

        Node node = new Node();
        node.setId("n1");
        node.setType("source");
        node.setName("Test Source");
        Node.NodeData data = new Node.NodeData();
        data.setSourceData("hello world");
        node.setData(data);

        // wire run id and execute
        nodeExecutor.setCurrentRunId("schema-1", run.getId());
        nodeExecutor.executeNode(node, "schema-1", new AtomicBoolean(false), com.agent.orchestrator.model.ExecutionMode.EXECUTE, null);

        List<NodeExecution> execs = executionRepository.getNodeExecutionsByRun(run.getId());
        assertFalse(execs.isEmpty());
        NodeExecution updated = execs.stream().filter(x -> x.getNodeId().equals("n1")).findFirst().orElse(null);
        assertNotNull(updated, "NodeExecution should exist");
        assertEquals("completed", updated.getStatus(), "NodeExecution should be marked completed");
        assertNotNull(updated.getOutputSummary(), "Output summary should be persisted");
        assertTrue(updated.getOutputSummary().contains("hello world"), "Output should contain source data");
        assertEquals(Node.NodeStatus.COMPLETED, node.getStatus(), "In-memory node status must reflect completion");
    }
}
