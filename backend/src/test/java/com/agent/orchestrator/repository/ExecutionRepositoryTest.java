package com.agent.orchestrator.repository;

import com.agent.orchestrator.config.DbConfig;
import com.agent.orchestrator.model.ExecutionCheckpoint;
import com.agent.orchestrator.model.ExecutionRun;
import com.agent.orchestrator.model.NodeExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionRepositoryTest {

    private ExecutionRepository repo;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        String dbPath = tempDir.resolve("test.db").toString();
        DbConfig config = new DbConfig(dbPath);
        repo = new ExecutionRepository(config);
    }

    @Test
    void createAndGetRun() {
        ExecutionRun run = new ExecutionRun();
        run.setId(UUID.randomUUID().toString());
        run.setSchemaId("schema-1");
        run.setStatus("running");
        run.setMode("EXECUTE");
        run.setStartedAt(java.time.Instant.now().toString());

        repo.createRun(run);

        ExecutionRun loaded = repo.getRun(run.getId());
        assertNotNull(loaded);
        assertEquals("running", loaded.getStatus());
        assertEquals("schema-1", loaded.getSchemaId());
    }

    @Test
    void updateRunStatus() {
        ExecutionRun run = new ExecutionRun();
        run.setId(UUID.randomUUID().toString());
        run.setSchemaId("schema-1");
        run.setStatus("running");
        run.setMode("EXECUTE");
        run.setStartedAt(java.time.Instant.now().toString());
        repo.createRun(run);

        repo.updateRunStatus(run.getId(), "paused", "Token limit");

        ExecutionRun loaded = repo.getRun(run.getId());
        assertEquals("paused", loaded.getStatus());
        assertEquals("Token limit", loaded.getError());
    }

    @Test
    void getRunsBySchema() {
        String schemaId = "schema-test";
        ExecutionRun r1 = new ExecutionRun();
        r1.setId(UUID.randomUUID().toString());
        r1.setSchemaId(schemaId);
        r1.setStatus("completed");
        r1.setMode("EXECUTE");
        r1.setStartedAt(java.time.Instant.now().toString());

        ExecutionRun r2 = new ExecutionRun();
        r2.setId(UUID.randomUUID().toString());
        r2.setSchemaId(schemaId);
        r2.setStatus("paused");
        r2.setMode("EXECUTE");
        r2.setStartedAt(java.time.Instant.now().toString());

        repo.createRun(r1);
        repo.createRun(r2);

        List<ExecutionRun> runs = repo.getRunsBySchema(schemaId);
        assertEquals(2, runs.size());
    }

    @Test
    void hasActiveRun() {
        String schemaId = "schema-active";
        ExecutionRun run = new ExecutionRun();
        run.setId(UUID.randomUUID().toString());
        run.setSchemaId(schemaId);
        run.setStatus("paused");
        run.setMode("EXECUTE");
        run.setStartedAt(java.time.Instant.now().toString());
        repo.createRun(run);

        assertTrue(repo.hasActiveRun(schemaId));
    }

    @Test
    void createAndGetNodeExecution() {
        String runId = UUID.randomUUID().toString();
        NodeExecution ne = new NodeExecution();
        ne.setId(UUID.randomUUID().toString());
        ne.setRunId(runId);
        ne.setNodeId("node-1");
        ne.setNodeName("Source");
        ne.setNodeType("source");
        ne.setStatus("completed");
        ne.setDurationMs(1500L);
        ne.setOutputSummary("{\"result\": \"ok\"}");
        ne.setStartedAt(java.time.Instant.now().toString());
        repo.createNodeExecution(ne);

        List<NodeExecution> nodes = repo.getNodeExecutionsByRun(runId);
        assertEquals(1, nodes.size());
        assertEquals("node-1", nodes.get(0).getNodeId());
        assertEquals("completed", nodes.get(0).getStatus());
    }

    @Test
    void updateNodeExecution() {
        String runId = UUID.randomUUID().toString();
        NodeExecution ne = new NodeExecution();
        ne.setId(UUID.randomUUID().toString());
        ne.setRunId(runId);
        ne.setNodeId("node-1");
        ne.setNodeName("Agent");
        ne.setNodeType("agent");
        ne.setStatus("running");
        ne.setStartedAt(java.time.Instant.now().toString());
        repo.createNodeExecution(ne);

        repo.updateNodeExecution(ne.getId(), "completed", "Done!", 150L, 2000L, 5, null);

        List<NodeExecution> nodes = repo.getNodeExecutionsByRun(runId);
        assertEquals(1, nodes.size());
        assertEquals("completed", nodes.get(0).getStatus());
        assertEquals("Done!", nodes.get(0).getOutputSummary());
        assertEquals(150L, nodes.get(0).getTokensUsed());
    }

    @Test
    void saveAndGetLatestCheckpoint() {
        String runId = UUID.randomUUID().toString();
        ExecutionCheckpoint cp = new ExecutionCheckpoint();
        cp.setId(UUID.randomUUID().toString());
        cp.setRunId(runId);
        cp.setCompletedNodeIds("[\"n1\",\"n2\"]");
        cp.setCurrentWave(1);
        cp.setCreatedAt(java.time.Instant.now().toString());
        repo.saveCheckpoint(cp);

        ExecutionCheckpoint loaded = repo.getLatestCheckpoint(runId);
        assertNotNull(loaded);
        assertEquals("[\"n1\",\"n2\"]", loaded.getCompletedNodeIds());
        assertEquals(1, loaded.getCurrentWave());
    }

    @Test
    void getLatestRunBySchemaAndStatus() {
        String schemaId = "schema-status-test";
        ExecutionRun r1 = new ExecutionRun();
        r1.setId(UUID.randomUUID().toString());
        r1.setSchemaId(schemaId);
        r1.setStatus("completed");
        r1.setMode("EXECUTE");
        r1.setStartedAt(java.time.Instant.now().toString());

        ExecutionRun r2 = new ExecutionRun();
        r2.setId(UUID.randomUUID().toString());
        r2.setSchemaId(schemaId);
        r2.setStatus("paused");
        r2.setMode("EXECUTE");
        r2.setStartedAt(java.time.Instant.now().toString());

        repo.createRun(r1);
        repo.createRun(r2);

        ExecutionRun found = repo.getLatestRunBySchemaAndStatus(schemaId, "paused");
        assertNotNull(found);
        assertEquals("paused", found.getStatus());
    }
}
