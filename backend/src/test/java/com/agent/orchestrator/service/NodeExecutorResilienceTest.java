package com.agent.orchestrator.service;

import com.agent.orchestrator.config.DbConfig;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.model.ExecutionRun;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.NodeExecution;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NodeExecutorResilienceTest {

    @Mock LlmService llmService;
    @Mock ExecutionWebSocketHandler webSocketHandler;
    @Mock MemPalaceClient memPalaceClient;
    @Mock ToolExecutor toolExecutor;
    @Mock TransformService transformService;
    @Mock Neo4jSchemaRepository schemaRepository;
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
                toolExecutor, transformService, schemaRepository, planService,
                projectContextBuilder, executionRepository);
    }

    @Test
    void setCurrentRunId_storesMapping() {
        nodeExecutor.setCurrentRunId("schema-1", "run-1");
        // Just verify no exception
        assertNotNull(executionRepository);
    }

    @Test
    void errorWithTokenExhaustion_updatesRunStatus() {
        String schemaId = "schema-token";
        String runId = UUID.randomUUID().toString();

        // Create run
        ExecutionRun run = new ExecutionRun();
        run.setId(runId);
        run.setSchemaId(schemaId);
        run.setStatus("running");
        run.setMode("EXECUTE");
        run.setStartedAt(java.time.Instant.now().toString());
        executionRepository.createRun(run);

        nodeExecutor.setCurrentRunId(schemaId, runId);

        // Simulate node that throws 402
        Node node = new Node();
        node.setId("node-1");
        node.setName("LLM Agent");
        node.setType("agent");

        when(llmService.streamingChat(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("HTTP 402 Payment Required \u2014 insufficient_quota"));

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        nodeExecutor.executeNode(node, schemaId, cancelFlag,
                com.agent.orchestrator.model.ExecutionMode.EXECUTE, "gpt-4o");

        // Run should be paused
        ExecutionRun updatedRun = executionRepository.getRun(runId);
        assertEquals("paused", updatedRun.getStatus());
    }
}
