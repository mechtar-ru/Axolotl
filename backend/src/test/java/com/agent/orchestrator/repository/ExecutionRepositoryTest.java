package com.agent.orchestrator.repository;

import com.agent.orchestrator.graph.model.GraphExecutionRun;
import com.agent.orchestrator.graph.model.GraphNodeExecution;
import com.agent.orchestrator.graph.repository.Neo4jCheckpointRepository;
import com.agent.orchestrator.graph.repository.Neo4jExecutionRecordRepository;
import com.agent.orchestrator.graph.repository.Neo4jExecutionRunRepository;
import com.agent.orchestrator.graph.repository.Neo4jNodeExecutionRepository;
import com.agent.orchestrator.model.ExecutionRun;
import com.agent.orchestrator.model.NodeExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionRepositoryTest {

    @Mock Neo4jExecutionRunRepository runRepo;
    @Mock Neo4jNodeExecutionRepository nodeExecRepo;
    @Mock Neo4jCheckpointRepository checkpointRepo;
    @Mock Neo4jExecutionRecordRepository recordRepo;

    @Captor ArgumentCaptor<GraphExecutionRun> graphRunCaptor;
    @Captor ArgumentCaptor<GraphNodeExecution> graphNodeCaptor;

    ExecutionRepository executionRepository;

    @BeforeEach
    void setUp() {
        executionRepository = new ExecutionRepository(runRepo, nodeExecRepo, checkpointRepo, recordRepo, new ObjectMapper());
    }

    // ── 1. saveRun ──

    @Test
    void createRun_savesAndMapsExecutionRun() {
        ExecutionRun run = new ExecutionRun();
        run.setId("run-1");
        run.setSchemaId("schema-1");
        run.setStatus("running");
        run.setMode("EXECUTE");
        run.setTotalTokens(100);
        run.setEstimatedCost(0.01);
        run.setStartedAt(Instant.parse("2025-01-01T00:00:00Z"));
        run.setResumeIndex(-1);

        executionRepository.createRun(run);

        verify(runRepo).save(graphRunCaptor.capture());
        GraphExecutionRun saved = graphRunCaptor.getValue();
        assertThat(saved.getId()).isEqualTo("run-1");
        assertThat(saved.getSchemaId()).isEqualTo("schema-1");
        assertThat(saved.getStatus()).isEqualTo("running");
        assertThat(saved.getMode()).isEqualTo("EXECUTE");
        assertThat(saved.getTotalTokens()).isEqualTo(100);
        assertThat(saved.getEstimatedCost()).isEqualTo(0.01);
        assertThat(saved.getStartedAt()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
        assertThat(saved.getResumeIndex()).isEqualTo(-1);
    }

    // ── 2. findRunById (found) ──

    @Test
    void getRun_whenFound_returnsMappedRun() {
        GraphExecutionRun graphRun = new GraphExecutionRun("run-1", "schema-1", "completed", "EXECUTE");
        graphRun.setTotalTokens(500);
        graphRun.setEstimatedCost(0.05);
        graphRun.setStartedAt(Instant.parse("2025-01-01T00:00:00Z"));
        graphRun.setUpdatedAt(Instant.parse("2025-01-01T01:00:00Z"));
        graphRun.setCompletedAt(Instant.parse("2025-01-01T01:00:00Z"));

        when(runRepo.findById("run-1")).thenReturn(Optional.of(graphRun));

        ExecutionRun result = executionRepository.getRun("run-1");

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("run-1");
        assertThat(result.getSchemaId()).isEqualTo("schema-1");
        assertThat(result.getStatus()).isEqualTo("completed");
        assertThat(result.getMode()).isEqualTo("EXECUTE");
        assertThat(result.getTotalTokens()).isEqualTo(500);
        assertThat(result.getEstimatedCost()).isEqualTo(0.05);
        assertThat(result.getStartedAt()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
    }

    // ── 3. findRunById (not found) ──

    @Test
    void getRun_whenNotFound_returnsNull() {
        when(runRepo.findById("nonexistent")).thenReturn(Optional.empty());

        ExecutionRun result = executionRepository.getRun("nonexistent");

        assertThat(result).isNull();
    }

    @Test
    void getRun_whenException_returnsNullGracefully() {
        when(runRepo.findById("broken")).thenThrow(new RuntimeException("DB down"));

        ExecutionRun result = executionRepository.getRun("broken");

        assertThat(result).isNull();
    }

    // ── 4. findLatestRunBySchema ──

    @Test
    void getLatestRunBySchema_returnsLatestFromList() {
        GraphExecutionRun oldRun = new GraphExecutionRun("run-1", "schema-1", "completed", "EXECUTE");
        oldRun.setStartedAt(Instant.parse("2025-01-01T00:00:00Z"));

        GraphExecutionRun latestRun = new GraphExecutionRun("run-2", "schema-1", "completed", "EXECUTE");
        latestRun.setStartedAt(Instant.parse("2025-01-02T00:00:00Z"));

        when(runRepo.findBySchemaIdOrderByStartedAtDesc("schema-1"))
                .thenReturn(List.of(latestRun, oldRun));

        ExecutionRun result = executionRepository.getLatestRunBySchema("schema-1");

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("run-2");
    }

    @Test
    void getLatestRunBySchema_whenNoRuns_returnsNull() {
        when(runRepo.findBySchemaIdOrderByStartedAtDesc("schema-empty"))
                .thenReturn(List.of());

        ExecutionRun result = executionRepository.getLatestRunBySchema("schema-empty");

        assertThat(result).isNull();
    }

    // ── 5. findBySchema (equiv to findByUserId requirement) ──

    @Test
    void getRunsBySchema_returnsAllRunsForSchema() {
        GraphExecutionRun run1 = new GraphExecutionRun("run-1", "schema-1", "completed", "EXECUTE");
        run1.setStartedAt(Instant.parse("2025-01-02T00:00:00Z"));
        GraphExecutionRun run2 = new GraphExecutionRun("run-2", "schema-1", "failed", "EXECUTE");
        run2.setStartedAt(Instant.parse("2025-01-01T00:00:00Z"));

        when(runRepo.findBySchemaIdOrderByStartedAtDesc("schema-1"))
                .thenReturn(List.of(run1, run2));

        List<ExecutionRun> results = executionRepository.getRunsBySchema("schema-1");

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getId()).isEqualTo("run-1");
        assertThat(results.get(1).getId()).isEqualTo("run-2");
    }

    @Test
    void getRunsBySchema_whenException_returnsEmptyList() {
        when(runRepo.findBySchemaIdOrderByStartedAtDesc("schema-1"))
                .thenThrow(new RuntimeException("DB error"));

        List<ExecutionRun> results = executionRepository.getRunsBySchema("schema-1");

        assertThat(results).isEmpty();
    }

    // ── 6. deleteRunsOlderThan ──

    @Test
    void deleteRunsOlderThan_convertsMillisToSecondsAndDelegates() {
        long cutoffMs = 1_700_000_000_000L; // roughly 2023-11-14

        executionRepository.deleteRunsOlderThan(cutoffMs);

        long expectedSeconds = cutoffMs / 1000;
        verify(runRepo).deleteRunsOlderThan(String.valueOf(expectedSeconds));
    }

    @Test
    void deleteRunsOlderThan_whenException_isCaught() {
        long cutoffMs = 1_000_000L;
        doThrow(new RuntimeException("DB error")).when(runRepo).deleteRunsOlderThan(anyString());

        // Should not throw
        executionRepository.deleteRunsOlderThan(cutoffMs);

        verify(runRepo).deleteRunsOlderThan(String.valueOf(cutoffMs / 1000));
    }

    // ── 7. deleteRun ──

    @Test
    void deleteRun_deletesNodeExecutionsThenRun() {
        executionRepository.deleteRun("run-1");

        verify(runRepo).deleteNodeExecutionsByRunId("run-1");
        verify(runRepo).deleteById("run-1");
    }

    @Test
    void deleteRun_whenException_throwsRuntimeException() {
        doThrow(new RuntimeException("DB error"))
                .when(runRepo).deleteNodeExecutionsByRunId("run-1");

        assertThatThrownBy(() -> executionRepository.deleteRun("run-1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to delete run run-1");
    }

    // ── 8. saveNodeExecution ──

    @Test
    void createNodeExecution_savesAndMapsNodeExecution() {
        NodeExecution ne = new NodeExecution();
        ne.setId("ne-1");
        ne.setRunId("run-1");
        ne.setNodeId("node-1");
        ne.setNodeName("Agent Node");
        ne.setNodeType("agent");
        ne.setStatus("running");
        ne.setStartedAt(Instant.parse("2025-01-01T00:00:00Z"));

        executionRepository.createNodeExecution(ne);

        verify(nodeExecRepo).save(graphNodeCaptor.capture());
        GraphNodeExecution saved = graphNodeCaptor.getValue();
        assertThat(saved.getId()).isEqualTo("ne-1");
        assertThat(saved.getRunId()).isEqualTo("run-1");
        assertThat(saved.getNodeId()).isEqualTo("node-1");
        assertThat(saved.getNodeName()).isEqualTo("Agent Node");
        assertThat(saved.getNodeType()).isEqualTo("agent");
        assertThat(saved.getStatus()).isEqualTo("running");
    }

    // ── 9. findNodeExecutionsByRun ──

    @Test
    void getNodeExecutionsByRun_returnsAllNodeExecutions() {
        GraphNodeExecution gne1 = new GraphNodeExecution();
        gne1.setId("ne-1");
        gne1.setRunId("run-1");
        gne1.setNodeType("agent");
        gne1.setStatus("completed");

        GraphNodeExecution gne2 = new GraphNodeExecution();
        gne2.setId("ne-2");
        gne2.setRunId("run-1");
        gne2.setNodeType("verifier");
        gne2.setStatus("completed");

        when(nodeExecRepo.findByRunIdOrderByStartedAtAsc("run-1"))
                .thenReturn(List.of(gne1, gne2));

        List<NodeExecution> results = executionRepository.getNodeExecutionsByRun("run-1");

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getId()).isEqualTo("ne-1");
        assertThat(results.get(0).getNodeType()).isEqualTo("agent");
        assertThat(results.get(1).getId()).isEqualTo("ne-2");
        assertThat(results.get(1).getNodeType()).isEqualTo("verifier");
    }

    @Test
    void getNodeExecutionsByRun_whenException_returnsEmptyList() {
        when(nodeExecRepo.findByRunIdOrderByStartedAtAsc("run-1"))
                .thenThrow(new RuntimeException("DB error"));

        List<NodeExecution> results = executionRepository.getNodeExecutionsByRun("run-1");

        assertThat(results).isEmpty();
    }

    // ── 10. updateNodeExecution (updateRunNodeResults equivalent) ──

    @Test
    void updateNodeExecution_findsAndUpdatesFields() {
        GraphNodeExecution graphNe = new GraphNodeExecution();
        graphNe.setId("ne-1");
        graphNe.setStatus("running");

        when(nodeExecRepo.findById("ne-1")).thenReturn(Optional.of(graphNe));

        executionRepository.updateNodeExecution("ne-1", "completed", "{\"result\":\"ok\"}",
                500, 1000, 3, null);

        ArgumentCaptor<GraphNodeExecution> captor = ArgumentCaptor.forClass(GraphNodeExecution.class);
        verify(nodeExecRepo).save(captor.capture());

        GraphNodeExecution saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo("ne-1");
        assertThat(saved.getStatus()).isEqualTo("completed");
        assertThat(saved.getOutputSummary()).isEqualTo("{\"result\":\"ok\"}");
        assertThat(saved.getTokensUsed()).isEqualTo(500);
        assertThat(saved.getDurationMs()).isEqualTo(1000);
        assertThat(saved.getToolCalls()).isEqualTo(3);
        assertThat(saved.getError()).isNull();
        assertThat(saved.getCompletedAt()).isNotNull();
    }

    @Test
    void updateNodeExecution_whenNotFound_doesNotSave() {
        when(nodeExecRepo.findById("ne-missing")).thenReturn(Optional.empty());

        executionRepository.updateNodeExecution("ne-missing", "completed", "output",
                100, 200, 1, null);

        verify(nodeExecRepo, never()).save(any());
    }

    @Test
    void updateNodeExecution_withReasoning_updatesReasoningField() {
        GraphNodeExecution graphNe = new GraphNodeExecution();
        graphNe.setId("ne-1");
        graphNe.setStatus("running");

        when(nodeExecRepo.findById("ne-1")).thenReturn(Optional.of(graphNe));

        executionRepository.updateNodeExecution("ne-1", "completed", "output",
                100, 200, 1, null, "thought process");

        ArgumentCaptor<GraphNodeExecution> captor = ArgumentCaptor.forClass(GraphNodeExecution.class);
        verify(nodeExecRepo).save(captor.capture());

        assertThat(captor.getValue().getReasoning()).isEqualTo("thought process");
    }

    // ── 11. Error handling: withRetry wraps exception in RuntimeException ──

    @Test
    void createRun_whenNonOptimisticException_throwsRuntimeException() {
        ExecutionRun run = new ExecutionRun();
        run.setId("run-1");
        run.setSchemaId("schema-1");
        run.setStatus("running");
        run.setMode("EXECUTE");

        doThrow(new IllegalStateException("Neo4j connection lost"))
                .when(runRepo).save(any(GraphExecutionRun.class));

        assertThatThrownBy(() -> executionRepository.createRun(run))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Neo4j write failed");
    }

    @Test
    void createRun_withOptimisticLockingRetriesThenFails() {
        ExecutionRun run = new ExecutionRun();
        run.setId("run-1");
        run.setSchemaId("schema-1");
        run.setStatus("running");
        run.setMode("EXECUTE");

        doThrow(new OptimisticLockingFailureException("lock conflict"))
                .when(runRepo).save(any(GraphExecutionRun.class));

        assertThatThrownBy(() -> executionRepository.createRun(run))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Neo4j write failed after retries");
    }

    // ── 12. getCompletedRuns with limit ──

    @Test
    void getCompletedRuns_capsLimitAt10() {
        GraphExecutionRun run = new GraphExecutionRun("run-1", "schema-1", "completed", "EXECUTE");
        run.setStartedAt(Instant.parse("2025-01-01T00:00:00Z"));

        // Request limit higher than max
        when(runRepo.findCompletedBySchemaId("schema-1", 10))
                .thenReturn(List.of(run));

        List<ExecutionRun> results = executionRepository.getCompletedRuns("schema-1", 100);

        assertThat(results).hasSize(1);
        verify(runRepo).findCompletedBySchemaId("schema-1", 10);
    }

    @Test
    void getCompletedRuns_passesThroughLimitUnder10() {
        GraphExecutionRun run = new GraphExecutionRun("run-1", "schema-1", "completed", "EXECUTE");
        run.setStartedAt(Instant.parse("2025-01-01T00:00:00Z"));

        when(runRepo.findCompletedBySchemaId("schema-1", 3))
                .thenReturn(List.of(run));

        List<ExecutionRun> results = executionRepository.getCompletedRuns("schema-1", 3);

        assertThat(results).hasSize(1);
        verify(runRepo).findCompletedBySchemaId("schema-1", 3);
    }

    @Test
    void getCompletedRuns_whenException_returnsEmptyList() {
        when(runRepo.findCompletedBySchemaId("schema-1", 10))
                .thenThrow(new RuntimeException("DB error"));

        List<ExecutionRun> results = executionRepository.getCompletedRuns("schema-1", 100);

        assertThat(results).isEmpty();
    }

    // ── Additional coverage: updateRunStatus ──

    @Test
    void updateRunStatus_findsAndUpdatesRun() {
        GraphExecutionRun graphRun = new GraphExecutionRun("run-1", "schema-1", "running", "EXECUTE");

        when(runRepo.findById("run-1")).thenReturn(Optional.of(graphRun));

        executionRepository.updateRunStatus("run-1", "completed", null);

        assertThat(graphRun.getStatus()).isEqualTo("completed");
        assertThat(graphRun.getError()).isNull();
        assertThat(graphRun.getUpdatedAt()).isNotNull();
        verify(runRepo).save(graphRun);
    }

    @Test
    void updateRunStatus_whenNotFound_doesNotSave() {
        when(runRepo.findById("run-missing")).thenReturn(Optional.empty());

        executionRepository.updateRunStatus("run-missing", "completed", null);

        verify(runRepo, never()).save(any());
    }

    // ── Additional coverage: findByStatus ──

    @Test
    void findByStatus_returnsRunsWithMatchingStatus() {
        GraphExecutionRun run = new GraphExecutionRun("run-1", "schema-1", "running", "EXECUTE");
        run.setStartedAt(Instant.parse("2025-01-01T00:00:00Z"));

        when(runRepo.findByStatus("running")).thenReturn(List.of(run));

        List<ExecutionRun> results = executionRepository.findByStatus("running");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getStatus()).isEqualTo("running");
    }

    @Test
    void findByStatus_whenException_returnsEmptyList() {
        when(runRepo.findByStatus("running")).thenThrow(new RuntimeException("DB error"));

        List<ExecutionRun> results = executionRepository.findByStatus("running");

        assertThat(results).isEmpty();
    }

    // ── Additional coverage: hasActiveRun ──

    @Test
    void hasActiveRun_delegatesToRepo() {
        when(runRepo.hasActiveRun("schema-1")).thenReturn(true);

        boolean result = executionRepository.hasActiveRun("schema-1");

        assertThat(result).isTrue();
    }

    @Test
    void hasActiveRun_whenException_returnsFalse() {
        when(runRepo.hasActiveRun("schema-1")).thenThrow(new RuntimeException("DB error"));

        boolean result = executionRepository.hasActiveRun("schema-1");

        assertThat(result).isFalse();
    }

    // ── Additional coverage: getRunById ──

    @Test
    void getRunById_returnsRunWhenFound() {
        GraphExecutionRun graphRun = new GraphExecutionRun("run-1", "schema-1", "completed", "EXECUTE");
        when(runRepo.findById("run-1")).thenReturn(Optional.of(graphRun));

        ExecutionRun result = executionRepository.getRunById("run-1");

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("run-1");
    }

    @Test
    void getRunById_whenException_returnsNull() {
        when(runRepo.findById("run-1")).thenThrow(new RuntimeException("DB error"));

        ExecutionRun result = executionRepository.getRunById("run-1");

        assertThat(result).isNull();
    }

    // ── Additional coverage: getLatestRunBySchemaAndStatus ──

    @Test
    void getLatestRunBySchemaAndStatus_returnsRunWhenFound() {
        GraphExecutionRun graphRun = new GraphExecutionRun("run-1", "schema-1", "completed", "EXECUTE");
        when(runRepo.findLatestBySchemaIdAndStatus("schema-1", "completed"))
                .thenReturn(Optional.of(graphRun));

        ExecutionRun result = executionRepository.getLatestRunBySchemaAndStatus("schema-1", "completed");

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("run-1");
        assertThat(result.getStatus()).isEqualTo("completed");
    }

    @Test
    void getLatestRunBySchemaAndStatus_whenNotFound_returnsNull() {
        when(runRepo.findLatestBySchemaIdAndStatus("schema-1", "running"))
                .thenReturn(Optional.empty());

        ExecutionRun result = executionRepository.getLatestRunBySchemaAndStatus("schema-1", "running");

        assertThat(result).isNull();
    }

    // ── Additional coverage: claimPausedRun ──

    @Test
    void claimPausedRun_returnsClaimedRun() {
        GraphExecutionRun graphRun = new GraphExecutionRun("run-1", "schema-1", "paused", "EXECUTE");
        when(runRepo.claimPausedRun("schema-1")).thenReturn(Optional.of(graphRun));

        ExecutionRun result = executionRepository.claimPausedRun("schema-1");

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("run-1");
    }

    // ── Additional coverage: getRunsBySchema empty ──

    @Test
    void getRunsBySchema_whenNoRuns_returnsEmptyList() {
        when(runRepo.findBySchemaIdOrderByStartedAtDesc("schema-empty"))
                .thenReturn(List.of());

        List<ExecutionRun> results = executionRepository.getRunsBySchema("schema-empty");

        assertThat(results).isEmpty();
    }

    // ── Additional coverage: updateRunGeneratedFiles ──

    @Test
    void updateRunGeneratedFiles_savesFilesAsJson() {
        GraphExecutionRun graphRun = new GraphExecutionRun("run-1", "schema-1", "running", "EXECUTE");
        when(runRepo.findById("run-1")).thenReturn(Optional.of(graphRun));

        executionRepository.updateRunGeneratedFiles("run-1", List.of("file1.js", "file2.js"));

        verify(runRepo).save(graphRun);
        assertThat(graphRun.getGeneratedFilesJson()).contains("file1.js", "file2.js");
    }

    // ── Additional coverage: releasePausedRun ──

    @Test
    void releasePausedRun_delegatesToRepo() {
        executionRepository.releasePausedRun("schema-1");

        verify(runRepo).releasePausedRun("schema-1");
    }

    @Test
    void releasePausedRun_whenException_isCaught() {
        doThrow(new RuntimeException("DB error")).when(runRepo).releasePausedRun("schema-1");

        // Should not throw
        executionRepository.releasePausedRun("schema-1");
    }

    // ── Additional coverage: claimSpecificRun ──

    @Test
    void claimSpecificRun_returnsClaimedRunWhenFound() {
        GraphExecutionRun graphRun = new GraphExecutionRun("run-1", "schema-1", "paused", "EXECUTE");
        when(runRepo.claimSpecificRun("run-1")).thenReturn(Optional.of(graphRun));

        ExecutionRun result = executionRepository.claimSpecificRun("run-1");

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("run-1");
    }

    @Test
    void claimSpecificRun_whenNotFound_returnsNull() {
        when(runRepo.claimSpecificRun("run-missing")).thenReturn(Optional.empty());

        ExecutionRun result = executionRepository.claimSpecificRun("run-missing");

        assertThat(result).isNull();
    }

    // ── Additional coverage: releaseStaleRuns ──

    @Test
    void releaseStaleRuns_delegatesAndReturnsCount() {
        when(runRepo.releaseStaleRuns("schema-1")).thenReturn(3L);

        int count = executionRepository.releaseStaleRuns("schema-1");

        assertThat(count).isEqualTo(3);
    }

    @Test
    void releaseStaleRuns_whenException_returnsZero() {
        when(runRepo.releaseStaleRuns("schema-1")).thenThrow(new RuntimeException("DB error"));

        int count = executionRepository.releaseStaleRuns("schema-1");

        assertThat(count).isEqualTo(0);
    }

    // ── Additional coverage: updateRunCompleted ──

    @Test
    void updateRunCompleted_setsAllCompletionFields() {
        GraphExecutionRun graphRun = new GraphExecutionRun("run-1", "schema-1", "running", "EXECUTE");
        when(runRepo.findById("run-1")).thenReturn(Optional.of(graphRun));

        executionRepository.updateRunCompleted("run-1", "completed", 1000, 0.25);

        verify(runRepo).save(graphRun);
        assertThat(graphRun.getStatus()).isEqualTo("completed");
        assertThat(graphRun.getTotalTokens()).isEqualTo(1000);
        assertThat(graphRun.getEstimatedCost()).isEqualTo(0.25);
        assertThat(graphRun.getCompletedAt()).isNotNull();
        assertThat(graphRun.getUpdatedAt()).isNotNull();
    }

    // ── Additional coverage: updateRunResumeIndex ──

    @Test
    void updateRunResumeIndex_delegatesToRepo() {
        executionRepository.updateRunResumeIndex("run-1", 5);

        verify(runRepo).updateStatusAndResumeIndex("run-1", null, 5);
    }

    // ── Additional coverage: updateRunPaused ──

    @Test
    void updateRunPaused_delegatesToRepo() {
        executionRepository.updateRunPaused("run-1", 3);

        verify(runRepo).updateStatusAndResumeIndex("run-1", "paused", 3);
    }

    // ── Additional coverage: updateRunResumeIndexOnly ──

    @Test
    void updateRunResumeIndexOnly_delegatesToRepo() {
        executionRepository.updateRunResumeIndexOnly("run-1", 7);

        verify(runRepo).updateResumeIndexOnly("run-1", 7);
    }

    // ── Additional coverage: getRun vs getRunById both return same mapping ──

    @Test
    void getRunAndGetRunById_consistency() {
        GraphExecutionRun graphRun = new GraphExecutionRun("same-run", "schema-1", "completed", "EXECUTE");
        graphRun.setTotalTokens(42);

        when(runRepo.findById("same-run")).thenReturn(Optional.of(graphRun));

        ExecutionRun viaGetRun = executionRepository.getRun("same-run");
        ExecutionRun viaGetRunById = executionRepository.getRunById("same-run");

        assertThat(viaGetRun).isNotNull();
        assertThat(viaGetRunById).isNotNull();
        assertThat(viaGetRun.getTotalTokens()).isEqualTo(42);
        assertThat(viaGetRunById.getTotalTokens()).isEqualTo(42);
    }

    // ── Additional coverage: getLatestRunBySchema exception ──

    @Test
    void getLatestRunBySchema_whenException_returnsNull() {
        when(runRepo.findBySchemaIdOrderByStartedAtDesc("schema-1"))
                .thenThrow(new RuntimeException("DB error"));

        ExecutionRun result = executionRepository.getLatestRunBySchema("schema-1");

        assertThat(result).isNull();
    }

    // ── Additional coverage: getRunsBySchema empty list ──

    @Test
    void getRunsBySchema_emptyList() {
        when(runRepo.findBySchemaIdOrderByStartedAtDesc("schema-empty"))
                .thenReturn(List.of());

        List<ExecutionRun> results = executionRepository.getRunsBySchema("schema-empty");

        assertThat(results).isEmpty();
    }

    // ── Additional coverage: updateNodeExecutionWithFiles ──

    @Test
    void updateNodeExecutionWithFiles_setsFilesWritten() {
        GraphNodeExecution graphNe = new GraphNodeExecution();
        graphNe.setId("ne-1");
        graphNe.setStatus("running");

        when(nodeExecRepo.findById("ne-1")).thenReturn(Optional.of(graphNe));

        executionRepository.updateNodeExecutionWithFiles("ne-1", "completed", "output",
                100, 200, 1, "[{\"path\":\"file.js\"}]", null);

        ArgumentCaptor<GraphNodeExecution> captor = ArgumentCaptor.forClass(GraphNodeExecution.class);
        verify(nodeExecRepo).save(captor.capture());

        assertThat(captor.getValue().getFilesWritten()).isEqualTo("[{\"path\":\"file.js\"}]");
    }
}
