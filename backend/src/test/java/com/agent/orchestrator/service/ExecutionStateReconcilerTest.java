package com.agent.orchestrator.service;

import com.agent.orchestrator.model.ExecutionRun;
import com.agent.orchestrator.model.NodeExecution;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionStateReconcilerTest {

    @Mock ExecutionRepository executionRepository;
    @Mock ExecutionStateManager stateManager;
    @Mock ExecutionWebSocketHandler webSocketHandler;

    ExecutionStateReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new ExecutionStateReconciler(
                executionRepository, stateManager, webSocketHandler);
    }

    @Test
    void reconcile_noRunningRuns_doesNothing() {
        when(executionRepository.findByStatus("running")).thenReturn(List.of());

        reconciler.reconcileOrphanedRuns();

        verify(executionRepository, never()).updateRunStatus(any(), any(), any());
    }

    @Test
    void reconcile_runningRunWithActiveState_skips() {
        ExecutionRun run = createRunningRun("run-1", "schema-1");
        when(executionRepository.findByStatus("running")).thenReturn(List.of(run));
        when(stateManager.getCurrentRunId("schema-1")).thenReturn("run-1");

        reconciler.reconcileOrphanedRuns();

        verify(executionRepository, never()).updateRunStatus(any(), any(), any());
    }

    @Test
    void reconcile_orphanedRun_reconcilesAndSendsEvents() {
        ExecutionRun run = createRunningRun("run-1", "schema-1");
        when(executionRepository.findByStatus("running")).thenReturn(List.of(run));
        when(stateManager.getCurrentRunId("schema-1")).thenReturn(null);
        when(executionRepository.getNodeExecutionsByRun("run-1")).thenReturn(List.of());

        reconciler.reconcileOrphanedRuns();

        verify(executionRepository).updateRunStatus("run-1", "RECONCILED_FAILED",
                "Execution was orphaned by server restart");
        verify(webSocketHandler).sendError(eq("schema-1"), anyString(), anyString(), any());
        verify(webSocketHandler).sendComplete("schema-1", 0, 0);
    }

    @Test
    void reconcile_nullSchemaId_skipsWebSocketEvents() {
        ExecutionRun run = createRunningRun("run-2", null);
        when(executionRepository.findByStatus("running")).thenReturn(List.of(run));

        reconciler.reconcileOrphanedRuns();

        verify(executionRepository).updateRunStatus("run-2", "RECONCILED_FAILED",
                "Execution was orphaned by server restart");
        verify(webSocketHandler, never()).sendError(any(), any(), any(), any());
        verify(webSocketHandler, never()).sendComplete(any(), anyInt(), anyInt());
    }

    private ExecutionRun createRunningRun(String id, String schemaId) {
        ExecutionRun run = new ExecutionRun();
        run.setId(id);
        run.setSchemaId(schemaId);
        run.setStatus("running");
        run.setStartedAt(Instant.now().minusSeconds(600));
        return run;
    }
}
