package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.model.GraphExecutionRun;
import com.agent.orchestrator.graph.repository.Neo4jExecutionRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StartupRecoveryServiceTest {

    @Mock Neo4jExecutionRunRepository runRepository;
    @Captor ArgumentCaptor<String> errorCaptor;

    StartupRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new StartupRecoveryService(runRepository);
    }

    @Test
    void recover_noStaleRuns_logsAndReturns() {
        when(runRepository.findStaleRuns()).thenReturn(List.of());

        service.recover();

        verify(runRepository, never()).forceUpdateRunStatus(any(), any(), any());
        verify(runRepository, never()).failRunningNodeExecutions(any(), any());
    }

    @Test
    void recover_nullStaleRuns_handlesGracefully() {
        when(runRepository.findStaleRuns()).thenReturn(null);

        service.recover();

        verify(runRepository, never()).forceUpdateRunStatus(any(), any(), any());
    }

    @Test
    void recover_singleRunningRun_marksFailed() {
        GraphExecutionRun run = createStaleRun("run-1", "running");
        when(runRepository.findStaleRuns()).thenReturn(List.of(run));

        service.recover();

        verify(runRepository).failRunningNodeExecutions("run-1", "Execution interrupted by process restart");
        verify(runRepository).forceUpdateRunStatus("run-1", "failed", "Execution interrupted by process restart");
    }

    @Test
    void recover_singlePausedRun_marksFailed() {
        GraphExecutionRun run = createStaleRun("run-2", "paused");
        when(runRepository.findStaleRuns()).thenReturn(List.of(run));

        service.recover();

        verify(runRepository).forceUpdateRunStatus("run-2", "failed", "Execution interrupted by process restart");
    }

    @Test
    void recover_multipleStaleRuns_allMarkedFailed() {
        GraphExecutionRun run1 = createStaleRun("run-1", "running");
        GraphExecutionRun run2 = createStaleRun("run-2", "paused");
        GraphExecutionRun run3 = createStaleRun("run-3", "running");
        when(runRepository.findStaleRuns()).thenReturn(List.of(run1, run2, run3));

        service.recover();

        verify(runRepository, times(3)).forceUpdateRunStatus(any(), eq("failed"), any());
        verify(runRepository, times(3)).failRunningNodeExecutions(any(), any());
    }

    @Test
    void recover_repositoryThrows_logsError() {
        when(runRepository.findStaleRuns()).thenThrow(new RuntimeException("Neo4j unavailable"));

        service.recover();

        verify(runRepository, never()).forceUpdateRunStatus(any(), any(), any());
    }

    @Test
    void recover_nodeExecFailure_marksRunAnyway() {
        GraphExecutionRun run = createStaleRun("run-1", "running");
        when(runRepository.findStaleRuns()).thenReturn(List.of(run));
        doThrow(new RuntimeException("DB error")).when(runRepository).failRunningNodeExecutions("run-1", "Execution interrupted by process restart");

        service.recover();

        verify(runRepository).forceUpdateRunStatus("run-1", "failed", "Execution interrupted by process restart");
    }

    private GraphExecutionRun createStaleRun(String id, String status) {
        GraphExecutionRun run = new GraphExecutionRun();
        run.setId(id);
        run.setStatus(status);
        run.setSchemaId("schema-" + id);
        return run;
    }
}
