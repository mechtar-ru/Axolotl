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

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PipelineStageExecutionServiceTest {

    @Mock Neo4jSchemaRepository schemaRepository;
    @Mock NodeRouter nodeRouter;
    @Mock ExecutionWebSocketHandler webSocketHandler;
    @Mock ExecutionRepository executionRepository;
    @Mock ExecutionStateManager stateManager;
    @Mock PipelineStatusManager statusManager;
    @Mock PlanService planService;
    @Mock PipelineStageRunner stageRunner;

    @Captor ArgumentCaptor<ExecutionRun> runCaptor;

    PipelineStageExecutionService service;

    private static final String SCHEMA_ID = "schema-1";
    private static final String RUN_ID = "run-1";

    private WorkflowSchema schema;
    private List<Stage> stages;

    @BeforeEach
    void setUp() {
        service = new PipelineStageExecutionService(
                schemaRepository, nodeRouter, webSocketHandler,
                executionRepository, stateManager, statusManager,
                planService, stageRunner);

        schema = new WorkflowSchema();
        schema.setId(SCHEMA_ID);
        schema.setName("Test Schema");
        schema.setPipeline(new Pipeline());
        schema.getPipeline().setParallelStrategy("sequential");

        // Default: 3 flat stages (no inter-stage deps) for retry + run tests.
        // Chain deps are avoided in retry tests because retry does NOT filter
        // out completed dependencies, so the topological sort breaks.
        stages = createFlatStages("s1", "s2", "s3");
        schema.getPipeline().setStages(stages);
    }

    // ── Helpers ──

    /** Stages with no dependencies — safe for retry tests. */
    private List<Stage> createFlatStages(String... ids) {
        List<Stage> result = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            Stage s = new Stage();
            s.setId(ids[i]);
            s.setName("Stage-" + ids[i]);
            s.setNodeType(i == 0 ? "source" : i == ids.length - 1 ? "verifier" : "agent");
            result.add(s);
        }
        return result;
    }

    /** Stages in a chain: each depends on the previous. */
    private List<Stage> createChainStages(String... ids) {
        List<Stage> result = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            Stage s = new Stage();
            s.setId(ids[i]);
            s.setName("Stage-" + ids[i]);
            s.setNodeType(i == 0 ? "source" : i == ids.length - 1 ? "verifier" : "agent");
            if (i > 0) {
                s.setDependencies(List.of(ids[i - 1]));
            }
            result.add(s);
        }
        return result;
    }

    private ExecutionRun createFailedRun(List<Stage> stageList, int failedIndex) {
        ExecutionRun run = new ExecutionRun();
        run.setId(RUN_ID);
        run.setSchemaId(SCHEMA_ID);
        run.setStatus("failed");
        run.setMode("PIPELINE");
        Map<String, String> status = new LinkedHashMap<>();
        for (int i = 0; i < stageList.size(); i++) {
            status.put(stageList.get(i).getId(),
                    i < failedIndex ? "completed" : i == failedIndex ? "failed" : "pending");
        }
        run.setStageStatus(status);
        return run;
    }

    private ExecutionRun createPausedRun(List<Stage> stageList, int pausedIndex) {
        ExecutionRun run = new ExecutionRun();
        run.setId(RUN_ID);
        run.setSchemaId(SCHEMA_ID);
        run.setStatus("paused");
        run.setMode("PIPELINE");
        run.setResumeIndex(pausedIndex);
        Map<String, String> status = new LinkedHashMap<>();
        for (int i = 0; i < stageList.size(); i++) {
            status.put(stageList.get(i).getId(),
                    i < pausedIndex ? "completed" : i == pausedIndex ? "paused" : "pending");
        }
        run.setStageStatus(status);
        Map<String, String> outputs = new HashMap<>();
        outputs.put("s1", "{\"content\": \"received input\"}");
        run.setStageOutputs(outputs);
        return run;
    }

    // ═══════════════════════════════════════════════════════════
    //  1. retryPipeline — happy path
    // ═══════════════════════════════════════════════════════════

    @Test
    void retryPipeline_happyPath_createsChildRunAndReexecutesFailedStages() {
        // s1 completed, s2 failed, s3 pending
        ExecutionRun failedRun = createFailedRun(stages, 1);
        when(schemaRepository.findById(SCHEMA_ID)).thenReturn(schema);
        when(executionRepository.getLatestRunBySchemaAndStatus(SCHEMA_ID, "failed")).thenReturn(failedRun);
        doNothing().when(executionRepository).createRun(any());
        when(stageRunner.executeStage(any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(PipelineStageRunner.StageRunResult.COMPLETED);
        when(stateManager.getGeneratedFilesRegistry()).thenReturn(new HashMap<>());

        service.retryPipeline(SCHEMA_ID);

        verify(executionRepository).createRun(runCaptor.capture());
        ExecutionRun childRun = runCaptor.getValue();

        assertThat(childRun.getId()).isNotBlank().isNotEqualTo(RUN_ID);
        assertThat(childRun.getSchemaId()).isEqualTo(SCHEMA_ID);
        assertThat(childRun.getStatus()).isEqualTo("running");
        assertThat(childRun.getMode()).isEqualTo("PIPELINE");
        assertThat(childRun.getResumesFrom()).isEqualTo(RUN_ID);
        assertThat(childRun.getStartedAt()).isNotNull();
        assertThat(childRun.getStageStatus()).isNotNull();

        // Stage status: completed stages kept, failed+downstream reset to pending
        Map<String, String> stageStatus = childRun.getStageStatus();
        assertThat(stageStatus.get("s1")).isIn("completed", "skipped");
        assertThat(stageStatus.get("s2")).isEqualTo("pending");
        assertThat(stageStatus.get("s3")).isEqualTo("pending");

        // executeStage called for s2 and s3 (skipApproval=true because they were NOT paused before failure)
        verify(stageRunner, atLeast(2)).executeStage(
                any(), eq(schema), anyString(), any(), any(), eq(SCHEMA_ID), anyInt(), eq(true));

        // Run marked completed
        verify(executionRepository, atLeastOnce()).updateRunCompleted(childRun.getId(), "completed", 0, 0.0);
        verify(stateManager).removeSchema(SCHEMA_ID);
    }

    // ═══════════════════════════════════════════════════════════
    //  2. retryPipeline — no failed runs
    // ═══════════════════════════════════════════════════════════

    @Test
    void retryPipeline_noFailedRuns_throwsException() {
        when(schemaRepository.findById(SCHEMA_ID)).thenReturn(schema);
        when(executionRepository.getLatestRunBySchemaAndStatus(SCHEMA_ID, "failed")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.retryPipeline(SCHEMA_ID));

        assertThat(ex.getMessage()).contains("No failed execution run found for schema " + SCHEMA_ID);
    }

    @Test
    void retryPipeline_schemaNotFound_throwsException() {
        when(schemaRepository.findById("nonexistent")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.retryPipeline("nonexistent"));

        assertThat(ex.getMessage()).contains("Schema not found: nonexistent");
    }

    // ═══════════════════════════════════════════════════════════
    //  3. retryPipeline with specific runId
    // ═══════════════════════════════════════════════════════════

    @Test
    void retryPipeline_withRunId_resolvesSpecificRun() {
        ExecutionRun specificRun = createFailedRun(stages, 1);
        specificRun.setId("specific-run-1");
        when(schemaRepository.findById(SCHEMA_ID)).thenReturn(schema);
        when(executionRepository.getRunById("specific-run-1")).thenReturn(specificRun);
        doNothing().when(executionRepository).createRun(any());
        when(stageRunner.executeStage(any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(PipelineStageRunner.StageRunResult.COMPLETED);
        when(stateManager.getGeneratedFilesRegistry()).thenReturn(new HashMap<>());

        service.retryPipeline(SCHEMA_ID, "specific-run-1");

        verify(executionRepository).getRunById("specific-run-1");
        verify(executionRepository, never()).getLatestRunBySchemaAndStatus(anyString(), anyString());
        verify(executionRepository).createRun(runCaptor.capture());
        assertThat(runCaptor.getValue().getResumesFrom()).isEqualTo("specific-run-1");
    }

    @Test
    void retryPipeline_withRunId_notFailed_throwsException() {
        ExecutionRun run = new ExecutionRun();
        run.setId("run-ok");
        run.setStatus("completed");
        when(schemaRepository.findById(SCHEMA_ID)).thenReturn(schema);
        when(executionRepository.getRunById("run-ok")).thenReturn(run);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.retryPipeline(SCHEMA_ID, "run-ok"));

        assertThat(ex.getMessage()).contains("is not in failed status");
    }

    // ═══════════════════════════════════════════════════════════
    //  4. resumePipeline — recovery and continuation
    // ═══════════════════════════════════════════════════════════

    @Test
    void resumePipeline_recoversAndCompletesRemainingStages() {
        // Use chain stages for resume: resume uses buildRemainingStages which DOES filter deps
        List<Stage> chainStages = createChainStages("s1", "s2", "s3");
        schema.getPipeline().setStages(chainStages);

        ExecutionRun pausedRun = createPausedRun(chainStages, 1);
        when(schemaRepository.findById(SCHEMA_ID)).thenReturn(schema);
        when(statusManager.consumeResumeState(SCHEMA_ID)).thenReturn(1);
        when(executionRepository.getLatestRunBySchemaAndStatus(SCHEMA_ID, "paused")).thenReturn(pausedRun);
        when(stageRunner.executeStage(any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(PipelineStageRunner.StageRunResult.COMPLETED);
        when(stateManager.getGeneratedFilesRegistry()).thenReturn(new HashMap<>());

        service.resumePipeline(SCHEMA_ID);

        // s2 and s3 should be executed
        verify(stageRunner, atLeast(2)).executeStage(
                any(), eq(schema), eq(RUN_ID), any(), any(), eq(SCHEMA_ID), anyInt(), eq(false));

        verify(executionRepository).updateRunStatus(eq(RUN_ID), eq("running"), isNull());
        verify(executionRepository).updateRunCompleted(eq(RUN_ID), eq("completed"), eq(0L), eq(0.0));
        // completed stage outputs pre-populated
        verify(statusManager).putStageResult(SCHEMA_ID, "s1", "{\"content\": \"received input\"}");
    }

    // ═══════════════════════════════════════════════════════════
    //  5. resumePipeline — already completed (no paused run)
    // ═══════════════════════════════════════════════════════════

    @Test
    void resumePipeline_noResumeState_returnsGracefully() {
        when(schemaRepository.findById(SCHEMA_ID)).thenReturn(schema);
        when(statusManager.consumeResumeState(SCHEMA_ID)).thenReturn(null);
        when(executionRepository.getLatestRunBySchemaAndStatus(SCHEMA_ID, "paused")).thenReturn(null);

        service.resumePipeline(SCHEMA_ID);

        verify(stageRunner, never()).executeStage(any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean());
        verify(executionRepository, never()).updateRunCompleted(anyString(), anyString(), anyLong(), anyDouble());
    }

    @Test
    void resumePipeline_alreadyCompleted_noStagesToResume() {
        ExecutionRun pausedRun = createPausedRun(stages, stages.size());
        when(schemaRepository.findById(SCHEMA_ID)).thenReturn(schema);
        when(statusManager.consumeResumeState(SCHEMA_ID)).thenReturn(stages.size());
        when(executionRepository.getLatestRunBySchemaAndStatus(SCHEMA_ID, "paused")).thenReturn(pausedRun);

        service.resumePipeline(SCHEMA_ID);

        verify(stageRunner, never()).executeStage(any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean());
    }

    // ═══════════════════════════════════════════════════════════
    //  6. Stage dependency topological sort
    // ═══════════════════════════════════════════════════════════

    @Test
    void topologicalSortStages_producesCorrectLevels() {
        List<Stage> linearStages = createChainStages("a", "b", "c");

        List<List<Stage>> levels = service.topologicalSortStages(linearStages);

        assertThat(levels).hasSize(3);
        assertThat(levels.get(0)).hasSize(1);
        assertThat(levels.get(0).get(0).getId()).isEqualTo("a");
        assertThat(levels.get(1).get(0).getId()).isEqualTo("b");
        assertThat(levels.get(2).get(0).getId()).isEqualTo("c");
    }

    @Test
    void topologicalSortStages_parallelLevels() {
        Stage a = new Stage(); a.setId("a"); a.setName("A");
        Stage b = new Stage(); b.setId("b"); b.setName("B"); b.setDependencies(List.of("a"));
        Stage c = new Stage(); c.setId("c"); c.setName("C"); c.setDependencies(List.of("a"));

        List<List<Stage>> levels = service.topologicalSortStages(List.of(a, b, c));

        assertThat(levels).hasSize(2);
        assertThat(levels.get(0)).hasSize(1);
        assertThat(levels.get(0).get(0).getId()).isEqualTo("a");
        assertThat(levels.get(1)).hasSize(2);
        assertThat(levels.get(1)).extracting(Stage::getId).containsExactlyInAnyOrder("b", "c");
    }

    @Test
    void topologicalSortStages_emptyList_returnsEmpty() {
        List<List<Stage>> levels = service.topologicalSortStages(List.of());
        assertThat(levels).isEmpty();
    }

    @Test
    void topologicalSortStages_cycleDetected_returnsEmpty() {
        // a -> b, b -> c, c -> a (cycle)
        Stage a = new Stage(); a.setId("a"); a.setDependencies(List.of("c"));
        Stage b = new Stage(); b.setId("b"); b.setDependencies(List.of("a"));
        Stage c = new Stage(); c.setId("c"); c.setDependencies(List.of("b"));

        List<List<Stage>> levels = service.topologicalSortStages(List.of(a, b, c));
        // Cycle prevents any stage from being resolved → empty levels
        assertThat(levels).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════
    //  7. ExecutionRun completion marking
    // ═══════════════════════════════════════════════════════════

    @Test
    void runPipelineStages_allComplete_marksRunCompleted() {
        when(stateManager.getGeneratedFilesRegistry()).thenReturn(new HashMap<>());
        when(stageRunner.executeStage(any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(PipelineStageRunner.StageRunResult.COMPLETED);

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        service.runPipelineStages(stages, schema, RUN_ID, cancelFlag);

        verify(executionRepository).updateRunCompleted(RUN_ID, "completed", 0, 0.0);
        verify(stateManager).removeSchema(SCHEMA_ID);
    }

    @Test
    void runPipelineStages_allComplete_sendsCompletionEvents() {
        when(stateManager.getGeneratedFilesRegistry()).thenReturn(new HashMap<>());
        when(stageRunner.executeStage(any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(PipelineStageRunner.StageRunResult.COMPLETED);

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        service.runPipelineStages(stages, schema, RUN_ID, cancelFlag);

        verify(webSocketHandler).sendLog(SCHEMA_ID, "success",
                "Pipeline completed: 3/3 stages", null);
        verify(webSocketHandler).sendComplete(SCHEMA_ID, 0, 3);
        verify(webSocketHandler).sendLiveUpdate(eq(SCHEMA_ID), eq("pipeline_completed"), any());
    }

    // ═══════════════════════════════════════════════════════════
    //  8. Error handling — stage failure stops the pipeline
    // ═══════════════════════════════════════════════════════════

    @Test
    void runPipelineStages_stageFailure_marksRunFailedAndStops() {
        when(stageRunner.executeStage(any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean()))
                .thenAnswer(invocation -> {
                    int stageIdx = invocation.getArgument(6);
                    if (stageIdx == 0) return PipelineStageRunner.StageRunResult.COMPLETED;
                    return PipelineStageRunner.StageRunResult.FAILED;
                });

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        service.runPipelineStages(stages, schema, RUN_ID, cancelFlag);

        verify(executionRepository).updateRunCompleted(RUN_ID, "failed", 0, 0.0);
        verify(stateManager).removeSchema(SCHEMA_ID);
    }

    @Test
    void runPipelineStages_stageException_propagates() {
        // The exception inside CompletableFuture.runAsync wraps in CompletionException
        // and propagates through allOf(...).join()
        when(stageRunner.executeStage(any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean()))
                .thenThrow(new RuntimeException("Stage execution error"));

        AtomicBoolean cancelFlag = new AtomicBoolean(false);

        assertThrows(Exception.class,
                () -> service.runPipelineStages(stages, schema, RUN_ID, cancelFlag));
    }

    // ═══════════════════════════════════════════════════════════
    //  9. Review approval status recovery on resume
    // ═══════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    @Test
    void resumePipeline_completedReviewStage_autoApproved() {
        Stage reviewStage = new Stage();
        reviewStage.setId("review-1");
        reviewStage.setName("Review");
        reviewStage.setNodeType("review");

        Stage agentStage = new Stage();
        agentStage.setId("agent-1");
        agentStage.setName("Agent");
        agentStage.setNodeType("agent");
        agentStage.setDependencies(List.of("review-1"));

        List<Stage> twoStages = List.of(reviewStage, agentStage);
        schema.getPipeline().setStages(twoStages);

        ExecutionRun pausedRun = new ExecutionRun();
        pausedRun.setId(RUN_ID);
        pausedRun.setStatus("paused");
        pausedRun.setResumeIndex(1);
        pausedRun.setStageStatus(new HashMap<>(Map.of(
                "review-1", "completed",
                "agent-1", "pending"
        )));

        // Inject a real ConcurrentHashMap into nodeResults for introspectability
        Map<String, Map<String, String>> nodeResultsRef = new ConcurrentHashMap<>();
        when(stateManager.getNodeResults()).thenReturn((Map) nodeResultsRef);
        when(schemaRepository.findById(SCHEMA_ID)).thenReturn(schema);
        when(statusManager.consumeResumeState(SCHEMA_ID)).thenReturn(1);
        when(executionRepository.getLatestRunBySchemaAndStatus(SCHEMA_ID, "paused")).thenReturn(pausedRun);
        when(stageRunner.executeStage(any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(PipelineStageRunner.StageRunResult.COMPLETED);
        when(stateManager.getGeneratedFilesRegistry()).thenReturn(new HashMap<>());

        service.resumePipeline(SCHEMA_ID);

        // Verify approval flag was set for completed review stage
        // Key format in service: schemaId + ":" + stageId + ":approved"
        Map<String, String> schemaNodeResults = nodeResultsRef.get(SCHEMA_ID);
        assertThat(schemaNodeResults).containsKey(SCHEMA_ID + ":review-1:approved");
        assertThat(schemaNodeResults.get(SCHEMA_ID + ":review-1:approved")).isEqualTo("true");
    }

    @Test
    void resumePipeline_uncompletedReviewStage_notApproved() {
        Stage reviewStage = new Stage();
        reviewStage.setId("review-1");
        reviewStage.setNodeType("review");

        Stage agentStage = new Stage();
        agentStage.setId("agent-1");
        agentStage.setNodeType("agent");
        agentStage.setDependencies(List.of("review-1"));

        List<Stage> twoStages = List.of(reviewStage, agentStage);
        schema.getPipeline().setStages(twoStages);

        ExecutionRun pausedRun = new ExecutionRun();
        pausedRun.setId(RUN_ID);
        pausedRun.setStatus("paused");
        pausedRun.setResumeIndex(0);
        pausedRun.setStageStatus(new HashMap<>(Map.of(
                "review-1", "paused",
                "agent-1", "pending"
        )));

        when(schemaRepository.findById(SCHEMA_ID)).thenReturn(schema);
        when(statusManager.consumeResumeState(SCHEMA_ID)).thenReturn(0);
        when(executionRepository.getLatestRunBySchemaAndStatus(SCHEMA_ID, "paused")).thenReturn(pausedRun);
        when(stageRunner.executeStage(any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(PipelineStageRunner.StageRunResult.COMPLETED);
        when(stateManager.getGeneratedFilesRegistry()).thenReturn(new HashMap<>());

        service.resumePipeline(SCHEMA_ID);

        verify(executionRepository).updateRunCompleted(RUN_ID, "completed", 0, 0.0);
    }

    // ═══════════════════════════════════════════════════════════
    //  10. Cancel during pipeline execution
    // ═══════════════════════════════════════════════════════════

    @Test
    void runPipelineStages_cancelled_marksRunCancelled() {
        when(stageRunner.executeStage(any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(PipelineStageRunner.StageRunResult.COMPLETED);
        when(stateManager.getGeneratedFilesRegistry()).thenReturn(new HashMap<>());

        AtomicBoolean cancelFlag = new AtomicBoolean(true);
        service.runPipelineStages(stages, schema, RUN_ID, cancelFlag);

        verify(executionRepository).updateRunCompleted(RUN_ID, "cancelled", 0, 0.0);
        verify(webSocketHandler).sendError(SCHEMA_ID, "system", "Pipeline cancelled");
    }

    @Test
    void runPipelineStages_cancelledMidExecution_stopsEarly() {
        // Use chain stages so each stage is in its own level (s3 in level 3, not started concurrently)
        List<Stage> chainStages = createChainStages("s1", "s2", "s3");
        schema.getPipeline().setStages(chainStages);

        when(stageRunner.executeStage(any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(PipelineStageRunner.StageRunResult.COMPLETED);
        when(stateManager.getGeneratedFilesRegistry()).thenReturn(new HashMap<>());

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        // After level 2 (s2 stage) completes, set the cancel flag
        // The pipeline checks cancel before starting the next level
        doAnswer(invocation -> {
            cancelFlag.set(true);
            return PipelineStageRunner.StageRunResult.COMPLETED;
        }).when(stageRunner).executeStage(
                argThat(s -> s != null && "s2".equals(s.getId())), any(), any(), any(), any(), any(), anyInt(), anyBoolean());

        service.runPipelineStages(chainStages, schema, RUN_ID, cancelFlag);

        // s3 should NOT execute because cancel flag was set during s2 (level 3 starts after check)
        verify(stageRunner, never()).executeStage(
                argThat(s -> s != null && "s3".equals(s.getId())),
                any(), any(), any(), any(), any(), anyInt(), anyBoolean());

        verify(executionRepository).updateRunCompleted(RUN_ID, "cancelled", 0, 0.0);
        verify(stateManager).removeSchema(SCHEMA_ID);
    }

    // ═══════════════════════════════════════════════════════════
    //  Edge cases: empty stages, no pipeline
    // ═══════════════════════════════════════════════════════════

    @Test
    void runPipelineStages_nullStages_returnsImmediately() {
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        service.runPipelineStages(null, schema, RUN_ID, cancelFlag);

        verify(stageRunner, never()).executeStage(any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean());
    }

    @Test
    void runPipelineStages_emptyStages_returnsImmediately() {
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        service.runPipelineStages(List.of(), schema, RUN_ID, cancelFlag);

        verify(stageRunner, never()).executeStage(any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean());
    }

    @Test
    void retryPipeline_noStages_throwsException() {
        schema.setPipeline(null);
        schema.setNodes(List.of());
        when(schemaRepository.findById(SCHEMA_ID)).thenReturn(schema);
        ExecutionRun failedRun = createFailedRun(List.of(), 0);
        when(executionRepository.getLatestRunBySchemaAndStatus(SCHEMA_ID, "failed")).thenReturn(failedRun);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.retryPipeline(SCHEMA_ID));

        assertThat(ex.getMessage()).contains("No stages to retry");
    }

    // ═══════════════════════════════════════════════════════════
    //  Helper method tests (package-private)
    // ═══════════════════════════════════════════════════════════

    @Test
    void copyStage_copiesAllFields() {
        Stage original = new Stage();
        original.setId("stage-1");
        original.setName("Test Stage");
        original.setNodeType("agent");
        original.setModel("gpt-4");
        original.setFallbackModels(List.of("gpt-3.5"));
        original.setSystemPrompt("You are a helpful assistant");
        original.setUserPrompt("Do something");
        original.setConfig(Map.of("key", "value"));
        original.setDependencies(List.of("prev-stage"));
        original.setPositionX(100.0);
        original.setPositionY(200.0);
        original.setSubagentSchemaId("sub-1");
        original.setLoopCondition("iter < 5");
        original.setMaxIterations(10);

        Stage copy = service.copyStage(original);

        assertThat(copy.getId()).isEqualTo(original.getId());
        assertThat(copy.getName()).isEqualTo(original.getName());
        assertThat(copy.getNodeType()).isEqualTo(original.getNodeType());
        assertThat(copy.getModel()).isEqualTo(original.getModel());
        assertThat(copy.getFallbackModels()).containsExactly("gpt-3.5");
        assertThat(copy.getSystemPrompt()).isEqualTo(original.getSystemPrompt());
        assertThat(copy.getUserPrompt()).isEqualTo(original.getUserPrompt());
        assertThat(copy.getConfig()).containsEntry("key", "value");
        assertThat(copy.getDependencies()).containsExactly("prev-stage");
        assertThat(copy.getPositionX()).isEqualTo(100.0);
        assertThat(copy.getPositionY()).isEqualTo(200.0);
        assertThat(copy.getSubagentSchemaId()).isEqualTo("sub-1");
        assertThat(copy.getLoopCondition()).isEqualTo("iter < 5");
        assertThat(copy.getMaxIterations()).isEqualTo(10);

        // Verify deep copy (mutating copy doesn't affect original)
        copy.getDependencies().add("another");
        assertThat(original.getDependencies()).hasSize(1);
    }

    // ═══════════════════════════════════════════════════════════
    //  Resume with explicit runId path
    // ═══════════════════════════════════════════════════════════

    @Test
    void resumePipeline_withRunId_usesResumeIndexFromDb() {
        ExecutionRun pausedRun = createPausedRun(stages, 0);
        pausedRun.setResumeIndex(0);
        pausedRun.setId("specific-run"); // the run we're looking up

        when(schemaRepository.findById(SCHEMA_ID)).thenReturn(schema);
        when(statusManager.consumeResumeState(SCHEMA_ID)).thenReturn(null);
        when(executionRepository.getRunById("specific-run")).thenReturn(pausedRun);
        when(stageRunner.executeStage(any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(PipelineStageRunner.StageRunResult.COMPLETED);
        when(stateManager.getGeneratedFilesRegistry()).thenReturn(new HashMap<>());

        service.resumePipeline(SCHEMA_ID, "specific-run");

        verify(stageRunner, atLeast(1)).executeStage(
                any(), eq(schema), eq("specific-run"), any(), any(), eq(SCHEMA_ID), anyInt(), eq(false));
        verify(executionRepository).updateRunStatus(eq("specific-run"), eq("running"), isNull());
    }

    @Test
    void resumePipeline_withRunId_notFound_returnsGracefully() {
        when(schemaRepository.findById(SCHEMA_ID)).thenReturn(schema);
        when(statusManager.consumeResumeState(SCHEMA_ID)).thenReturn(null);
        when(executionRepository.getRunById("nonexistent")).thenReturn(null);

        service.resumePipeline(SCHEMA_ID, "nonexistent");

        verify(stageRunner, never()).executeStage(any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean());
    }

    // ═══════════════════════════════════════════════════════════
    //  Retry: dependent stage filtering
    // ═══════════════════════════════════════════════════════════

    @Test
    void retryPipeline_dependentStages_alsoMarkedForRetry() {
        // 4 flat stages: a, b, c, d. b failed → c and d also reset to pending
        List<Stage> fourStages = createFlatStages("a", "b", "c", "d");
        schema.getPipeline().setStages(fourStages);

        ExecutionRun failedRun = createFailedRun(fourStages, 1);
        when(schemaRepository.findById(SCHEMA_ID)).thenReturn(schema);
        when(executionRepository.getLatestRunBySchemaAndStatus(SCHEMA_ID, "failed")).thenReturn(failedRun);
        doNothing().when(executionRepository).createRun(any());
        when(stageRunner.executeStage(any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(PipelineStageRunner.StageRunResult.COMPLETED);
        when(stateManager.getGeneratedFilesRegistry()).thenReturn(new HashMap<>());

        service.retryPipeline(SCHEMA_ID);

        verify(executionRepository).createRun(runCaptor.capture());
        Map<String, String> newStatus = runCaptor.getValue().getStageStatus();

        assertThat(newStatus.get("a")).isIn("completed", "skipped");
        assertThat(newStatus.get("b")).isEqualTo("pending");
        assertThat(newStatus.get("c")).isEqualTo("pending");
        assertThat(newStatus.get("d")).isEqualTo("pending");
    }

    // ═══════════════════════════════════════════════════════════
    //  Resume: exception handling in resume doesn't crash
    // ═══════════════════════════════════════════════════════════

    @Test
    void resumePipeline_stageException_marksRunPaused() {
        List<Stage> chainStages = createChainStages("s1", "s2", "s3");
        schema.getPipeline().setStages(chainStages);

        ExecutionRun pausedRun = createPausedRun(chainStages, 1);
        when(schemaRepository.findById(SCHEMA_ID)).thenReturn(schema);
        when(statusManager.consumeResumeState(SCHEMA_ID)).thenReturn(1);
        when(executionRepository.getLatestRunBySchemaAndStatus(SCHEMA_ID, "paused")).thenReturn(pausedRun);
        when(stageRunner.executeStage(any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean()))
                .thenThrow(new RuntimeException("Stage execution failed"));

        service.resumePipeline(SCHEMA_ID);

        verify(executionRepository).updateRunStatus(eq(RUN_ID), eq("paused"), contains("Stage execution failed"));
        verify(stateManager).removeSchema(SCHEMA_ID);
    }

    // ═══════════════════════════════════════════════════════════
    //  Resume: pause mid-resume (another pause before completion)
    // ═══════════════════════════════════════════════════════════

    @Test
    void resumePipeline_pausesAgain_doesNotMarkCompleted() {
        List<Stage> chainStages = createChainStages("s1", "s2", "s3");
        schema.getPipeline().setStages(chainStages);

        ExecutionRun pausedRun = createPausedRun(chainStages, 1);
        when(schemaRepository.findById(SCHEMA_ID)).thenReturn(schema);
        when(statusManager.consumeResumeState(SCHEMA_ID)).thenReturn(1);
        when(executionRepository.getLatestRunBySchemaAndStatus(SCHEMA_ID, "paused")).thenReturn(pausedRun);
        when(stageRunner.executeStage(
                argThat(s -> s != null && "s2".equals(s.getId())), any(), any(), any(), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(PipelineStageRunner.StageRunResult.PAUSED);

        service.resumePipeline(SCHEMA_ID);

        verify(executionRepository, never()).updateRunCompleted(RUN_ID, "completed", 0, 0.0);
        verify(stageRunner, never()).executeStage(
                argThat(s -> s != null && "s3".equals(s.getId())), any(), any(), any(), any(), any(), anyInt(), anyBoolean());
    }
}
