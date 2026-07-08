package com.agent.orchestrator.service;

import com.agent.orchestrator.model.*;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class PipelineServiceImpl implements PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineServiceImpl.class);

    private final Neo4jSchemaRepository schemaRepository;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final ExecutionRepository executionRepository;
    private final ExecutionStateManager stateManager;
    private final SchemaValidator schemaValidator;
    private final PipelineBuilder pipelineBuilder;
    private final PipelineStatusManager statusManager;
    private final PipelineStageExecutionService stageExecutionService;
    private final ExecutorService pipelineExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public PipelineServiceImpl(Neo4jSchemaRepository schemaRepository,
                               ExecutionWebSocketHandler webSocketHandler,
                               ExecutionRepository executionRepository,
                               ExecutionStateManager stateManager,
                               SchemaValidator schemaValidator,
                               PipelineBuilder pipelineBuilder,
                               PipelineStatusManager statusManager,
                               PipelineStageExecutionService stageExecutionService) {
        this.schemaRepository = schemaRepository;
        this.webSocketHandler = webSocketHandler;
        this.executionRepository = executionRepository;
        this.stateManager = stateManager;
        this.schemaValidator = schemaValidator;
        this.pipelineBuilder = pipelineBuilder;
        this.statusManager = statusManager;
        this.stageExecutionService = stageExecutionService;
    }

    void clearStaleApprovals(String schemaId) {
        stageExecutionService.clearStaleApprovals(schemaId);
    }

    @Override
    public WorkflowSchema buildPipelineNodes(String schemaId) {
        return pipelineBuilder.buildNodes(schemaId);
    }

    @Transactional
    @Override
    public void executePipeline(String schemaId) {
        WorkflowSchema schema = schemaRepository.findById(schemaId);
        if (schema == null) {
            throw new RuntimeException("Schema not found: " + schemaId);
        }
        if (schema.getPipeline() == null) {
            throw new RuntimeException("Schema has no pipeline definition");
        }

        SchemaValidationResult validation = schemaValidator.validate(schema);
        if (!validation.isValid()) {
            throw new SchemaValidationException(validation);
        }

        CompletableFuture<?> existing = statusManager.getRunningFuture(schemaId);
        if (existing != null && !existing.isDone()) {
            log.warn("Pipeline already running: {}", schemaId);
            throw new RuntimeException("Pipeline '" + schemaId + "' is already running");
        }

        String runId = UUID.randomUUID().toString();
        ExecutionRun run = new ExecutionRun();
        run.setId(runId);
        run.setSchemaId(schemaId);
        run.setStatus("running");
        run.setMode("PIPELINE");
        run.setStartedAt(Instant.now());
        run.setUpdatedAt(Instant.now());
        PipelineFactory.initializeRunStageStatus(run, schema.getPipeline().getStages());
        executionRepository.createRun(run);

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        clearStaleApprovals(schemaId);

        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "info", "Pipeline execution started: " + schema.getName(), null);
            webSocketHandler.sendProgress(schemaId, "system", "PIPELINE_STARTED", 0, "Pipeline started");
        }

        List<Stage> stages = schema.getPipeline().getStages();
        launchStageExecution(schemaId, schema, stages, runId, cancelFlag);
    }

    @Transactional
    @Override
    public void executeDerivedStages(String schemaId, WorkflowSchema schema, List<Stage> stages, String sessionInput) {
        CompletableFuture<?> existing = statusManager.getRunningFuture(schemaId);
        if (existing != null && !existing.isDone()) {
            log.warn("Pipeline already running: {}", schemaId);
            throw new RuntimeException("Pipeline '" + schemaId + "' is already running");
        }

        SchemaValidationResult validation = schemaValidator.validate(schema);
        if (!validation.isValid()) {
            throw new SchemaValidationException(validation);
        }

        String runId = UUID.randomUUID().toString();
        ExecutionRun run = new ExecutionRun();
        run.setId(runId);
        run.setSchemaId(schemaId);
        run.setStatus("running");
        run.setMode("EXECUTE");
        run.setSessionInput(sessionInput);
        run.setStartedAt(Instant.now());
        run.setUpdatedAt(Instant.now());
        PipelineFactory.initializeRunStageStatus(run, stages);
        executionRepository.createRun(run);

        // Register in state-manager so the reconciler knows this run is active on this JVM
        stateManager.setCurrentRunId(schemaId, runId);

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        clearStaleApprovals(schemaId);

        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "info", "Execution started: " + schema.getName(), null);
            webSocketHandler.sendProgress(schemaId, "system", "EXECUTION_STARTED", 0, "Execution started");
        }

        launchStageExecution(schemaId, schema, stages, runId, cancelFlag);
    }

    private void launchStageExecution(String schemaId, WorkflowSchema schema, List<Stage> stages,
                                       String runId, AtomicBoolean cancelFlag) {
        CompletableFuture<?> future = CompletableFuture.runAsync(
                () -> stageExecutionService.runPipelineStages(stages, schema, runId, cancelFlag),
                pipelineExecutor);
                statusManager.registerPipeline(schemaId, future, cancelFlag);

        future.whenComplete((result, ex) -> {
            statusManager.removeFuture(schemaId);
            statusManager.removeCancelFlag(schemaId);
            // Clear from state-manager so reconciler doesn't skip checking this in future
            stateManager.setCurrentRunId(schemaId, null);
            if (ex != null) {
                if (ex instanceof CancellationException || ex.getCause() instanceof CancellationException) {
                    log.info("Execution cancelled for schema {}", schemaId);
                    executionRepository.updateRunStatus(runId, "cancelled", "Cancelled by user");
                } else {
                    log.error("Execution failed for {}: {}", schemaId, ex.getMessage(), ex);
                    executionRepository.updateRunStatus(runId, "failed", ex.getMessage());
                }
            }
            try {
                WorkflowSchema s = schemaRepository.findById(schemaId);
                if (s != null) {
                    s.setLastRunAt(Instant.now());
                    schemaRepository.save(s);
                }
            } catch (Exception e) {
                log.warn("Failed to update lastRunAt for schema {}: {}", schemaId, e.getMessage(), e);
            }
        });
    }

    @Override
    public void retryPipeline(String schemaId) {
        stageExecutionService.retryPipeline(schemaId);
    }

    @Override
    public void retryPipeline(String schemaId, String runId) {
        stageExecutionService.retryPipeline(schemaId, runId);
    }

    @Override
    public void resumePipeline(String schemaId) {
        stageExecutionService.resumePipeline(schemaId);
    }

    @Override
    public void resumePipeline(String schemaId, String runId) {
        stageExecutionService.resumePipeline(schemaId, runId);
    }

    @Override
    public void cancelPipeline(String schemaId) {
        statusManager.cancelPipeline(schemaId);
    }

    @Override
    public Map<String, String> getStageResults(String schemaId) {
        return statusManager.getStageResults(schemaId);
    }

    @Override
    public boolean isPipelineRunning(String schemaId) {
        return statusManager.isPipelineRunning(schemaId);
    }

    @PreDestroy
    public void shutdown() {
        pipelineExecutor.shutdownNow();
    }
}
