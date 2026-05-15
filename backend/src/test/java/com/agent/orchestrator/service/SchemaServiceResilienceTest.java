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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SchemaServiceResilienceTest {

    @Mock Neo4jSchemaRepository schemaRepository;
    @Mock ExecutionWebSocketHandler webSocketHandler;
    @Mock MemPalaceClient memPalaceClient;
    @Mock SettingsService settingsService;
    @Mock MetricsService metricsService;
    @Mock NodeExecutor nodeExecutor;
    @Mock SchemaExporter schemaExporter;
    @Mock LlmService llmService;

    private ExecutionRepository executionRepository;
    private SchemaService schemaService;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        String dbPath = tempDir.resolve("test.db").toString();
        DbConfig config = new DbConfig(dbPath);
        executionRepository = new ExecutionRepository(config);
        PlanService planService = mock(PlanService.class);

        schemaService = new SchemaService(schemaRepository, webSocketHandler, memPalaceClient,
                settingsService, metricsService, nodeExecutor, schemaExporter, llmService,
                planService, executionRepository);
    }

    @Test
    void computeConfigHash_sameConfig_sameHash() {
        Node node1 = new Node();
        node1.setId("n1");
        node1.setName("Agent");
        node1.setType("agent");
        Node.NodeData data1 = new Node.NodeData();
        data1.setModel("gpt-4o");
        data1.setUserPrompt("Analyze");
        node1.setData(data1);

        Node node2 = new Node();
        node2.setId("n2");
        node2.setName("Agent");
        node2.setType("agent");
        Node.NodeData data2 = new Node.NodeData();
        data2.setModel("gpt-4o");
        data2.setUserPrompt("Analyze");
        node2.setData(data2);

        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("test-schema");
        schema.setNodes(List.of(node1, node2));
        schema.setEdges(List.of());

        String hash1 = schemaService.computeConfigHash(node1, schema);
        String hash2 = schemaService.computeConfigHash(node2, schema);

        assertEquals(hash1, hash2, "Одинаковая конфигурация → одинаковый хеш");
    }

    @Test
    void computeConfigHash_differentConfig_differentHash() {
        Node node1 = new Node();
        node1.setId("n1");
        node1.setType("agent");
        Node.NodeData data1 = new Node.NodeData();
        data1.setModel("gpt-4o");
        node1.setData(data1);

        Node node2 = new Node();
        node2.setId("n2");
        node2.setType("agent");
        Node.NodeData data2 = new Node.NodeData();
        data2.setModel("claude-3");
        node2.setData(data2);

        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("test-schema");
        schema.setNodes(List.of(node1, node2));
        schema.setEdges(List.of());

        String hash1 = schemaService.computeConfigHash(node1, schema);
        String hash2 = schemaService.computeConfigHash(node2, schema);

        assertNotEquals(hash1, hash2, "Разная конфигурация → разный хеш");
    }

    @Test
    void computeConfigHash_differentEdges_differentHash() {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("test-schema");

        Node node = new Node();
        node.setId("n1");
        node.setType("agent");
        Node.NodeData data = new Node.NodeData();
        data.setModel("gpt-4o");
        node.setData(data);
        schema.setNodes(List.of(node));

        Edge edge = new Edge();
        edge.setId("e1");
        edge.setSource("source-1");
        edge.setTarget("n1");
        schema.setEdges(List.of(edge));

        String hashWithEdge = schemaService.computeConfigHash(node, schema);

        schema.setEdges(List.of());
        String hashWithoutEdge = schemaService.computeConfigHash(node, schema);

        assertNotEquals(hashWithEdge, hashWithoutEdge,
                "Разные входящие рёбра → разный хеш");
    }

    @Test
    void findExecutionRuns_returnsRuns() {
        ExecutionRun run = new ExecutionRun();
        run.setId(UUID.randomUUID().toString());
        run.setSchemaId("schema-runs-test");
        run.setStatus("completed");
        run.setMode("EXECUTE");
        run.setStartedAt(java.time.Instant.now().toString());
        executionRepository.createRun(run);

        List<ExecutionRun> runs = schemaService.findExecutionRuns("schema-runs-test");
        assertFalse(runs.isEmpty());
        assertEquals("completed", runs.get(0).getStatus());
    }

    @Test
    void getPausedRun_returnsNullWhenNoPausedRun() {
        ExecutionRun run = new ExecutionRun();
        run.setId(UUID.randomUUID().toString());
        run.setSchemaId("schema-no-pause");
        run.setStatus("completed");
        run.setMode("EXECUTE");
        run.setStartedAt(java.time.Instant.now().toString());
        executionRepository.createRun(run);

        ExecutionRun paused = schemaService.getPausedRun("schema-no-pause");
        assertNull(paused);
    }

    @Test
    void getPausedRun_returnsPausedRun() {
        ExecutionRun run = new ExecutionRun();
        run.setId(UUID.randomUUID().toString());
        run.setSchemaId("schema-paused");
        run.setStatus("paused");
        run.setMode("EXECUTE");
        run.setStartedAt(java.time.Instant.now().toString());
        executionRepository.createRun(run);

        ExecutionRun paused = schemaService.getPausedRun("schema-paused");
        assertNotNull(paused);
        assertEquals("paused", paused.getStatus());
    }
}
