package com.agent.orchestrator.service;

import com.agent.orchestrator.model.*;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    private final Neo4jSchemaRepository schemaRepository;
    private final NodeExecutor nodeExecutor;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final ExecutionRepository executionRepository;
    private final ExecutionStateManager stateManager;
    private final SchemaValidator schemaValidator;
    private final PipelineBuilder pipelineBuilder;
    private final PipelineStatusManager statusManager;
    private final DiffService diffService;
    private final ExecutorService pipelineExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final ObjectMapper mapper = new ObjectMapper();

    /** Per-stage timeout — 20 minutes default. Stages exceeding this are failed. */
    private static final Duration STAGE_TIMEOUT = Duration.ofMinutes(20);

    enum StageRunResult { COMPLETED, PAUSED, FAILED }

    public PipelineService(Neo4jSchemaRepository schemaRepository,
                           NodeExecutor nodeExecutor,
                           ExecutionWebSocketHandler webSocketHandler,
                           ExecutionRepository executionRepository,
                           ExecutionStateManager stateManager,
                           SchemaValidator schemaValidator,
                           PipelineBuilder pipelineBuilder,
                           PipelineStatusManager statusManager,
                           DiffService diffService) {
        this.schemaRepository = schemaRepository;
        this.nodeExecutor = nodeExecutor;
        this.webSocketHandler = webSocketHandler;
        this.executionRepository = executionRepository;
        this.stateManager = stateManager;
        this.schemaValidator = schemaValidator;
        this.pipelineBuilder = pipelineBuilder;
        this.statusManager = statusManager;
        this.diffService = diffService;
    }

    private void clearStaleApprovals(String schemaId) {
        statusManager.clearStaleApprovals(schemaId, stateManager != null ? stateManager.getNodeResults() : null);
    }

    public WorkflowSchema buildPipelineNodes(String schemaId) {
        return pipelineBuilder.buildNodes(schemaId);
    }

    @Transactional
    public void executePipeline(String schemaId) {
        WorkflowSchema schema = schemaRepository.findById(schemaId);
        if (schema == null) {
            throw new RuntimeException("Schema not found: " + schemaId);
        }
        if (schema.getPipeline() == null) {
            throw new RuntimeException("Schema has no pipeline definition");
        }

        // Validate schema before execution
        SchemaValidationResult validation = schemaValidator.validate(schema);
        if (!validation.isValid()) {
            throw new SchemaValidationException(validation);
        }

        CompletableFuture<?> existing = statusManager.getRunningPipelines().get(schemaId);
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
        run.setStartedAt(Instant.now().toString());
        run.setUpdatedAt(Instant.now().toString());
        initializeRunStageStatus(run, schema.getPipeline().getStages());
        executionRepository.createRun(run);

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        statusManager.getCancelFlags().put(schemaId, cancelFlag);
        statusManager.getStageResults().put(schemaId, new ConcurrentHashMap<>());
        clearStaleApprovals(schemaId);

        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "info", "Pipeline execution started: " + schema.getName(), null);
            webSocketHandler.sendProgress(schemaId, "system", "PIPELINE_STARTED", 0, "Pipeline started");
        }

        List<Stage> stages = schema.getPipeline().getStages();
        launchStageExecution(schemaId, schema, stages, runId, cancelFlag);
    }

    /**
     * Execute stages derived from canvas nodes (no Pipeline definition required).
     * Called by SchemaService.executeSchema() when schema has no explicit Pipeline.
     */
    @Transactional
    public void executeDerivedStages(String schemaId, WorkflowSchema schema, List<Stage> stages) {
        CompletableFuture<?> existing = statusManager.getRunningPipelines().get(schemaId);
        if (existing != null && !existing.isDone()) {
            log.warn("Pipeline already running: {}", schemaId);
            throw new RuntimeException("Pipeline '" + schemaId + "' is already running");
        }

        // Validate schema before execution
        SchemaValidationResult validation = schemaValidator.validate(schema);
        if (!validation.isValid()) {
            throw new SchemaValidationException(validation);
        }

        String runId = UUID.randomUUID().toString();
        ExecutionRun run = new ExecutionRun();
        run.setId(runId);
        run.setSchemaId(schemaId);
        run.setStatus("running");
        run.setMode("EXECUTE"); // derived stages = EXECUTE mode (not PIPELINE)
        run.setStartedAt(Instant.now().toString());
        run.setUpdatedAt(Instant.now().toString());
        initializeRunStageStatus(run, stages);
        executionRepository.createRun(run);

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        statusManager.getCancelFlags().put(schemaId, cancelFlag);
        statusManager.getStageResults().put(schemaId, new ConcurrentHashMap<>());
        clearStaleApprovals(schemaId);

        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "info", "Execution started: " + schema.getName(), null);
            webSocketHandler.sendProgress(schemaId, "system", "EXECUTION_STARTED", 0, "Execution started");
        }

        launchStageExecution(schemaId, schema, stages, runId, cancelFlag);
    }

    /**
     * Common launch stub for both executePipeline and executeDerivedStages.
     * Creates the async future that runs stages.
     */
    private void launchStageExecution(String schemaId, WorkflowSchema schema, List<Stage> stages,
                                       String runId, AtomicBoolean cancelFlag) {
        CompletableFuture<?> future = CompletableFuture.runAsync(
                () -> runStages(stages, schema, runId, cancelFlag), pipelineExecutor);
        statusManager.getRunningPipelines().put(schemaId, future);

        future.whenComplete((result, ex) -> {
            statusManager.getRunningPipelines().remove(schemaId);
            statusManager.getCancelFlags().remove(schemaId);
            if (ex != null) {
                if (ex instanceof CancellationException || ex.getCause() instanceof CancellationException) {
                    log.info("Execution cancelled for schema {}", schemaId);
                    executionRepository.updateRunStatus(runId, "cancelled", "Cancelled by user");
                } else {
                    log.error("Execution failed for {}: {}", schemaId, ex.getMessage(), ex);
                    executionRepository.updateRunStatus(runId, "failed", ex.getMessage());
                }
            }
            // Update lastRunAt on the schema after execution completes (any terminal state)
            try {
                WorkflowSchema s = schemaRepository.findById(schemaId);
                if (s != null) {
                    s.setLastRunAt(Instant.now().toString());
                    schemaRepository.save(s);
                    log.debug("Updated lastRunAt for schema: {}", schemaId);
                }
            } catch (Exception e) {
                log.warn("Failed to update lastRunAt for schema {}: {}", schemaId, e.getMessage());
            }
        });
    }

    /**
     * Run stages directly (the core execution loop).
     * Used by executePipeline (schema.pipeline stages) and executeDerivedStages (canvas-derived stages).
     */
    private void runStages(List<Stage> stages, WorkflowSchema schema, String runId, AtomicBoolean cancelFlag) {
        if (stages == null || stages.isEmpty()) return;

        Map<String, Node> nodeMap = new HashMap<>();
        if (schema.getNodes() != null) {
            for (Node n : schema.getNodes()) nodeMap.put(n.getId(), cloneNode(n));
        }

        List<List<Stage>> stageLevels = topologicalSortStages(stages);

        int totalStages = stages.size();
        int completedStages = 0;

        for (List<Stage> level : stageLevels) {
            if (cancelFlag.get()) break;
            if (level.isEmpty()) continue;

            if (webSocketHandler != null) {
                List<String> stageIds = level.stream().map(Stage::getId).toList();
                webSocketHandler.sendLog(schema.getId(), "info", "Pipeline level: " + stageIds, null);
                webSocketHandler.sendLiveUpdate(schema.getId(), "pipeline_level",
                        Map.of("stageIds", stageIds, "status", "running"));
            }

            // Assign sequential indices before launching parallel execution
            // Run stages within a level concurrently
            java.util.concurrent.CompletableFuture<Void>[] futures = new java.util.concurrent.CompletableFuture[level.size()];
            boolean[] levelPaused = { false };
            boolean[] levelFailed = { false };

            for (int si = 0; si < level.size(); si++) {
                Stage stage = level.get(si);
                int stageIdx = completedStages + si;
                futures[si] = java.util.concurrent.CompletableFuture.runAsync(() -> {
                    if (cancelFlag.get() || levelPaused[0] || levelFailed[0]) return;
                    StageRunResult result = executeSingleStage(stage, schema, runId, nodeMap,
                            cancelFlag, schema.getId(), stageIdx, false);
                    if (result == StageRunResult.PAUSED) {
                        synchronized (levelPaused) { levelPaused[0] = true; }
                    } else if (result == StageRunResult.FAILED) {
                        synchronized (levelFailed) { levelFailed[0] = true; }
                    }
                });
            }
            java.util.concurrent.CompletableFuture.allOf(futures).join();

            if (levelFailed[0]) {
                log.warn("Pipeline level failed, stopping pipeline for schema {}", schema.getId());
                executionRepository.updateRunCompleted(runId, "failed", 0, 0.0);
                break;
            }
            if (levelPaused[0]) break;

            completedStages += level.size();
            int progress = (int) ((double) completedStages / totalStages * 100);
            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schema.getId(), "system", "PIPELINE_PROGRESS",
                        progress, completedStages + "/" + totalStages + " stages completed");
            }
        }

        boolean isPaused = statusManager.getPipelineResumeState().containsKey(schema.getId());

        if (webSocketHandler != null) {
            if (cancelFlag.get() && !isPaused) {
                webSocketHandler.sendError(schema.getId(), "system", "Pipeline cancelled");
            } else if (isPaused) {
                webSocketHandler.sendLog(schema.getId(), "info",
                        "Pipeline paused: " + completedStages + "/" + totalStages + " stages completed", null);
            } else {
                webSocketHandler.sendLog(schema.getId(), "success",
                        "Pipeline completed: " + completedStages + "/" + totalStages + " stages", null);
                webSocketHandler.sendComplete(schema.getId(), 0, completedStages);
                webSocketHandler.sendLiveUpdate(schema.getId(), "pipeline_completed",
                        Map.of("status", "completed", "stagesCompleted", completedStages));
            }
        }

        if (!isPaused) {
            executionRepository.updateRunCompleted(runId, cancelFlag.get() ? "cancelled" : "completed", 0, 0.0);
            stateManager.removeSchema(schema.getId());
        }
    }

    public void retryPipeline(String schemaId) {
        WorkflowSchema schema = schemaRepository.findById(schemaId);
        if (schema == null) {
            throw new RuntimeException("Schema not found: " + schemaId);
        }

        ExecutionRun failedRun = executionRepository.getLatestRunBySchemaAndStatus(schemaId, "failed");
        if (failedRun == null) {
            throw new RuntimeException("No failed execution run found for schema " + schemaId);
        }

        // Derive stages from pipeline or canvas nodes
        List<Stage> stages;
        if (schema.getPipeline() != null && schema.getPipeline().getStages() != null
                && !schema.getPipeline().getStages().isEmpty()) {
            stages = schema.getPipeline().getStages();
        } else {
            stages = createStagesFromNodes(schema);
        }
        if (stages.isEmpty()) {
            throw new RuntimeException("No stages to retry (no pipeline or canvas nodes found)");
        }

        Map<String, String> persistedStatus = failedRun.getStageStatus();
        int firstFailedIndex = -1;
        for (int i = 0; i < stages.size(); i++) {
            String sid = stages.get(i).getId();
            String st = persistedStatus != null ? persistedStatus.get(sid) : null;
            if ("failed".equals(st)) {
                firstFailedIndex = i;
                break;
            }
        }
        if (firstFailedIndex == -1) {
            throw new RuntimeException("No failed stages found in run " + failedRun.getId()
                    + " — cannot retry");
        }

        // Create child run
        String runId = UUID.randomUUID().toString();
        ExecutionRun run = new ExecutionRun();
        run.setId(runId);
        run.setSchemaId(schemaId);
        run.setStatus("running");
        run.setMode(failedRun.getMode() != null ? failedRun.getMode() : "PIPELINE");
        run.setResumesFrom(failedRun.getId());
        run.setStartedAt(Instant.now().toString());
        run.setUpdatedAt(Instant.now().toString());

        // Copy stage status from failed run, resetting failed + downstream to pending
        Map<String, String> newStatus = new HashMap<>();
        for (Stage s : stages) {
            String sid = s.getId();
            String oldSt = persistedStatus != null ? persistedStatus.get(sid) : null;
            boolean isFailedOrAfter = false;
            for (int j = firstFailedIndex; j < stages.size(); j++) {
                if (stages.get(j).getId().equals(sid)) {
                    isFailedOrAfter = true;
                    break;
                }
            }
            if (isFailedOrAfter) {
                newStatus.put(sid, "pending");
            } else {
                newStatus.put(sid, oldSt != null ? oldSt : "skipped");
            }
        }
        run.setStageStatus(newStatus);
        executionRepository.createRun(run);

        // Notify
        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schema.getId(), "info", "Retrying pipeline from stage "
                    + stages.get(firstFailedIndex).getName(), null);
            webSocketHandler.sendProgress(schema.getId(), "system", "PIPELINE_RETRY",
                    (int) ((double) firstFailedIndex / stages.size() * 100),
                    "Retrying from stage " + (firstFailedIndex + 1) + "/" + stages.size());
        }

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        statusManager.getCancelFlags().put(schemaId, cancelFlag);
        statusManager.getStageResults().put(schemaId, new ConcurrentHashMap<>());

        // Re-run stages from first failed onwards — same logic as inner loop of runPipelineStages
        Map<String, Node> nodeMap = new HashMap<>();
        if (schema.getNodes() != null) {
            for (Node n : schema.getNodes()) nodeMap.put(n.getId(), cloneNode(n));
        }

        // Track stages that were paused before the failure — these need approval during retry
        Set<String> pausedBeforeFailure = new HashSet<>();
        for (int i = 0; i < stages.size(); i++) {
            if (i >= firstFailedIndex) break;
            String sid = stages.get(i).getId();
            String st = persistedStatus != null ? persistedStatus.get(sid) : null;
            if ("paused".equals(st)) {
                pausedBeforeFailure.add(sid);
            }
        }

        // Only run stages at or after firstFailedIndex
        List<Stage> retryStages = new ArrayList<>();
        Set<String> completedBefore = new HashSet<>();
        for (int i = 0; i < stages.size(); i++) {
            if (i >= firstFailedIndex) {
                Stage s = stages.get(i);
                Stage copy = copyStage(s);
                // Filter deps to only those completed in the original run
                List<String> filtered = new ArrayList<>();
                if (s.getDependencies() != null) {
                    for (String dep : s.getDependencies()) {
                        String depSt = persistedStatus != null ? persistedStatus.get(dep) : null;
                        if ("completed".equals(depSt)) {
                            filtered.add(dep);
                        } else if (i < firstFailedIndex) {
                            filtered.add(dep);
                        }
                        // Failed deps of retried stages stay — topological sort will error if cyclic
                    }
                }
                copy.setDependencies(filtered);
                retryStages.add(copy);
            } else {
                completedBefore.add(stages.get(i).getId());
            }
        }

        // Pre-populate stageResults with completed stages' outputs from the original run
        if (failedRun.getStageOutputs() != null) {
            for (String completedId : completedBefore) {
                String output = failedRun.getStageOutputs().get(completedId);
                if (output != null) {
                    statusManager.getStageResults().get(schemaId).put(completedId, output);
                }
            }
        }

        List<List<Stage>> retryLevels = topologicalSortStages(retryStages);
        int completedRetry = 0;
        int retryStageIndex = firstFailedIndex;
        for (List<Stage> level : retryLevels) {
            if (cancelFlag.get()) break;
            for (Stage stage : level) {
                if (cancelFlag.get()) break;

                boolean retrySkipApproval = !pausedBeforeFailure.contains(stage.getId());
                StageRunResult result = executeSingleStage(stage, schema, runId, nodeMap,
                        cancelFlag, schemaId, retryStageIndex, retrySkipApproval);
                retryStageIndex++;
                if (result == StageRunResult.FAILED) {
                    executionRepository.updateRunCompleted(runId, "failed", 0, 0.0);
                    statusManager.getCancelFlags().remove(schemaId);
                    statusManager.getStageResults().remove(schemaId);
                    return;
                }
                // PAUSED with skipApprovalCheck=true: the stage was force-completed.
                // COMPLETED: normal success. Either way the stage is done.
            }
            completedRetry += level.size();
        }

        if (cancelFlag.get()) {
            executionRepository.updateRunCompleted(runId, "cancelled", 0, 0.0);
        } else {
            executionRepository.updateRunCompleted(runId, "completed", 0, 0.0);
            if (webSocketHandler != null) {
                int totalCompleted = completedBefore.size() + completedRetry;
                webSocketHandler.sendLog(schema.getId(), "success",
                        "Pipeline retry completed: " + totalCompleted + "/" + stages.size()
                                + " stages", null);
                webSocketHandler.sendComplete(schema.getId(), 0, totalCompleted);
                webSocketHandler.sendLiveUpdate(schema.getId(), "pipeline_completed",
                        Map.of("status", "completed", "stagesCompleted", totalCompleted));
            }
        }

        statusManager.getCancelFlags().remove(schemaId);
        statusManager.getStageResults().remove(schemaId);
    }

    public void resumePipeline(String schemaId) {
        resumePipeline(schemaId, null);
    }

    public void resumePipeline(String schemaId, String runId) {
        WorkflowSchema schema = schemaRepository.findById(schemaId);
        if (schema == null) {
            throw new RuntimeException("Schema not found: " + schemaId);
        }

        Integer resumeIndex = statusManager.getPipelineResumeState().remove(schemaId);
        ExecutionRun run = null;

        if (runId != null) {
            run = executionRepository.getRunById(runId);
            if (run == null) {
                log.warn("No pipeline run found by id {} for schema {}", runId, schemaId);
                return;
            }
            // Derive resumeIndex from persist DB field (always prefer this over in-memory)
            int dbIndex = run.getResumeIndex();
            if (dbIndex >= 0) {
                resumeIndex = dbIndex;
            } else if (resumeIndex == null) {
                log.warn("No resume index for run {} in schema {}", runId, schemaId);
                return;
            }
        } else if (resumeIndex == null) {
            // Try the DB — the index may have been persisted before a restart
            ExecutionRun pausedRun = executionRepository.getLatestRunBySchemaAndStatus(schemaId, "paused");
            if (pausedRun == null) {
                log.warn("No pipeline resume state found for schema {}", schemaId);
                return;
            }
            int dbIndex = pausedRun.getResumeIndex();
            if (dbIndex < 0) {
                log.warn("No resume index in DB for schema {}", schemaId);
                return;
            }
            resumeIndex = dbIndex;
            run = pausedRun;
        } else {
            run = executionRepository.getLatestRunBySchemaAndStatus(schemaId, "paused");
            if (run == null) {
                log.warn("No paused pipeline run found for schema {}", schemaId);
                return;
            }
        }

        // Derive stages from pipeline or canvas nodes
        List<Stage> stages;
        if (schema.getPipeline() != null && schema.getPipeline().getStages() != null
                && !schema.getPipeline().getStages().isEmpty()) {
            stages = schema.getPipeline().getStages();
        } else {
            stages = createStagesFromNodes(schema);
        }
        if (stages.isEmpty() || resumeIndex >= stages.size()) {
            log.warn("No stages to resume for schema {}", schemaId);
            return;
        }

        // Set approval flag so review nodes are skipped
        if (resumeIndex > 0) {
            Stage previousStage = stages.get(resumeIndex - 1);
            if ("review".equals(previousStage.getNodeType())) {
                String approvedKey = schemaId + ":" + previousStage.getId() + ":approved";
                nodeExecutor.getNodeResults()
                        .computeIfAbsent(schemaId, k -> new ConcurrentHashMap<>())
                        .put(approvedKey, "true");
                log.info("Set approval flag for review stage {}", previousStage.getId());
            }
        }

        // Build remaining stages in-memory only (don't save truncated schema to Neo4j)
        List<Stage> remainingStages = new ArrayList<>();
        Set<String> completedStageIds = new HashSet<>();
        for (int i = 0; i < resumeIndex; i++) {
            completedStageIds.add(stages.get(i).getId());
        }
        for (int i = resumeIndex; i < stages.size(); i++) {
            Stage s = stages.get(i);
            Stage copy = new Stage();
            copy.setId(s.getId());
            copy.setName(s.getName());
            copy.setNodeType(s.getNodeType());
            copy.setModel(s.getModel());
            copy.setSystemPrompt(s.getSystemPrompt());
            copy.setUserPrompt(s.getUserPrompt());
            copy.setConfig(s.getConfig());
            copy.setPositionX(s.getPositionX());
            copy.setPositionY(s.getPositionY());
            List<String> filteredDeps = new ArrayList<>();
            if (s.getDependencies() != null) {
                for (String dep : s.getDependencies()) {
                    if (!completedStageIds.contains(dep)) {
                        filteredDeps.add(dep);
                    }
                }
            }
            copy.setDependencies(filteredDeps);
            remainingStages.add(copy);
        }

        Map<String, Node> nodeMap = new HashMap<>();
        if (schema.getNodes() != null) {
            for (Node n : schema.getNodes()) nodeMap.put(n.getId(), cloneNode(n));
        }

        List<List<Stage>> remainingLevels = topologicalSortStages(remainingStages);
        int totalRemaining = remainingStages.size();
        int completedRemaining = 0;
        int initialCompleted = resumeIndex;

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        statusManager.getCancelFlags().put(schemaId, cancelFlag);
        statusManager.getStageResults().put(schemaId, new ConcurrentHashMap<>());

        // Pre-populate stageResults with completed stages' outputs (from the initial run that paused)
        if (run.getStageOutputs() != null) {
            for (String completedId : completedStageIds) {
                String output = run.getStageOutputs().get(completedId);
                if (output != null) {
                    statusManager.getStageResults().get(schemaId).put(completedId, output);
                }
            }
        }

        boolean resumedPaused = false;
        int resumeStageIndex = resumeIndex;
        try {
            // Mark run as running now that we're inside the catchable scope
            executionRepository.updateRunStatus(run.getId(), "running", null);

            for (List<Stage> level : remainingLevels) {
                if (cancelFlag.get()) break;

                for (Stage stage : level) {
                    if (cancelFlag.get() || resumedPaused) break;

                    StageRunResult result = executeSingleStage(stage, schema, run.getId(), nodeMap,
                            cancelFlag, schemaId, resumeStageIndex, false);
                    resumeStageIndex++;
                    if (result == StageRunResult.PAUSED) {
                        resumedPaused = true;
                        break;
                    } else if (result == StageRunResult.FAILED) {
                        // Continue to next stage in level
                    }
                }

                if (resumedPaused) break;
                completedRemaining += level.size();
            }
        } catch (Exception e) {
            ExecutionError error = ExecutionError.fromException(e, "Unknown error resuming pipeline");
            log.error("Pipeline resume failed for schema {}: {} ({})", schemaId, error.getMessage(), error.getType(), e);
            executionRepository.updateRunStatus(run.getId(), "paused", error.getMessage());
            if (webSocketHandler != null) {
                webSocketHandler.sendError(schema.getId(), "system",
                        "Pipeline resume failed: " + error.getMessage());
            }
            return;
        }

        if (resumedPaused) {
            // Already handled above — don't mark completed
        } else if (webSocketHandler != null) {
            if (cancelFlag.get()) {
                webSocketHandler.sendError(schema.getId(), "system", "Pipeline cancelled");
            } else {
                int totalCompleted = initialCompleted + completedRemaining;
                webSocketHandler.sendLog(schema.getId(), "success",
                        "Pipeline completed: " + totalCompleted + "/" + stages.size() + " stages", null);
                webSocketHandler.sendComplete(schema.getId(), 0, totalCompleted);
                webSocketHandler.sendLiveUpdate(schema.getId(), "pipeline_completed",
                        Map.of("status", "completed", "stagesCompleted", totalCompleted));
            }
        }

        if (!resumedPaused) {
            executionRepository.updateRunCompleted(run.getId(), cancelFlag.get() ? "cancelled" : "completed", 0, 0.0);
        }
    }

    private List<List<Stage>> topologicalSortStages(List<Stage> stages) {
        Map<String, Set<String>> deps = new HashMap<>();
        for (Stage s : stages) {
            deps.put(s.getId(), new HashSet<>(s.getDependencies() != null ? s.getDependencies() : List.of()));
        }

        List<List<Stage>> levels = new ArrayList<>();
        Set<String> processed = new HashSet<>();

        while (processed.size() < stages.size()) {
            List<Stage> level = new ArrayList<>();
            for (Stage s : stages) {
                if (processed.contains(s.getId())) continue;
                deps.get(s.getId()).removeAll(processed);
                if (deps.get(s.getId()).isEmpty()) {
                    level.add(s);
                }
            }
            if (level.isEmpty()) {
                log.warn("Cycle detected in pipeline stages, breaking");
                break;
            }
            for (Stage s : level) processed.add(s.getId());
            levels.add(level);
        }
        return levels;
    }

    private Stage copyStage(Stage s) {
        Stage copy = new Stage();
        copy.setId(s.getId());
        copy.setName(s.getName());
        copy.setNodeType(s.getNodeType());
        copy.setModel(s.getModel());
        copy.setFallbackModels(s.getFallbackModels() != null ? new ArrayList<>(s.getFallbackModels()) : null);
        copy.setSystemPrompt(s.getSystemPrompt());
        copy.setUserPrompt(s.getUserPrompt());
        copy.setConfig(s.getConfig() != null ? new HashMap<>(s.getConfig()) : null);
        copy.setPositionX(s.getPositionX());
        copy.setPositionY(s.getPositionY());
        if (s.getDependencies() != null) {
            copy.setDependencies(new ArrayList<>(s.getDependencies()));
        }
        if (s.getSubagentSchemaId() != null) {
            copy.setSubagentSchemaId(s.getSubagentSchemaId());
        }
        if (s.getLoopCondition() != null) {
            copy.setLoopCondition(s.getLoopCondition());
            copy.setMaxIterations(s.getMaxIterations());
        }
        return copy;
    }

    private Node cloneNode(Node original) {
        return mapper.convertValue(original, Node.class);
    }

    private Node stageToScratchNode(Stage stage, String schemaId) {
        Node node = new Node();
        node.setId(stage.getId() != null ? stage.getId() : "stage-" + UUID.randomUUID().toString().substring(0, 8));
        node.setType(stage.getNodeType() != null ? stage.getNodeType() : "agent");
        node.setName(stage.getName() != null ? stage.getName() : "Scratch Stage");
        node.setStatus(Node.NodeStatus.IDLE);

        Node.NodeData data = new Node.NodeData();
        data.setModel(stage.getModel());
        data.setSystemPrompt(stage.getSystemPrompt());
        data.setUserPrompt(stage.getUserPrompt());
        data.setConfig(stage.getConfig());
        if (stage.getSubagentSchemaId() != null) {
            if (data.getConfig() == null) data.setConfig(new HashMap<>());
            data.getConfig().put("subagentSchemaId", stage.getSubagentSchemaId());
        }
        node.setData(data);

        node.setInputPorts(List.of("in"));
        node.setOutputPorts(List.of("out"));
        return node;
    }

    private String resolveStageModel(Stage stage, WorkflowSchema schema) {
        if (stage.getModel() != null && !stage.getModel().isBlank()) return stage.getModel();
        return schema.getDefaultModel();
    }

    public void cancelPipeline(String schemaId) {
        statusManager.cancelPipeline(schemaId);
    }

    public Map<String, String> getStageResults(String schemaId) {
        return statusManager.getStageResults(schemaId);
    }

    public boolean isPipelineRunning(String schemaId) {
        return statusManager.isPipelineRunning(schemaId);
    }

    /**
     * Resolve input mappings for a stage by pulling output fields from upstream stages
     * stored in statusManager.getStageResults(). Handles both "sourceStageId.fieldName" (JSON field extraction)
     * and "sourceStageId" (entire result as value) patterns.
     * Operates on a defensive copy of the config to avoid mutating schema nodes in-place.
     */
    private void resolveInputMappings(Stage stage, Node.NodeData data, String schemaId) {
        Map<String, String> mapping = stage.getInputMapping();
        if (mapping == null || mapping.isEmpty() || data == null) return;

        // Clone config to avoid mutating the shared schema node
        Map<String, Object> config = new HashMap<>();
        if (data.getConfig() != null) config.putAll(data.getConfig());
        data.setConfig(config);

        Map<String, String> results = statusManager.getStageResults().get(schemaId);
        if (results == null) return;

        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String sourceKey = entry.getKey();
            String targetField = entry.getValue();

            int dotIdx = sourceKey.indexOf('.');
            String sourceStageId;
            String fieldName;
            if (dotIdx < 0) {
                sourceStageId = sourceKey;
                fieldName = null; // use entire result
            } else {
                sourceStageId = sourceKey.substring(0, dotIdx);
                fieldName = sourceKey.substring(dotIdx + 1);
            }

            String sourceResult = results.get(sourceStageId);
            if (sourceResult == null || sourceResult.isEmpty()) {
                log.warn("Input mapping: source '{}' for stage '{}' has no result (stage may not have run)", sourceStageId, stage.getId());
                continue;
            }

            try {
                if (fieldName == null) {
                    config.put(targetField, sourceResult);
                } else {
                    JsonNode json = mapper.readTree(sourceResult);
                    JsonNode field = json.get(fieldName);
                    if (field != null) {
                        config.put(targetField, field.isTextual() ? field.asText() : field.toString());
                    }
                }
                log.debug("Resolved input mapping: {} -> {} = {}", sourceKey, targetField, config.get(targetField));
            } catch (Exception e) {
                log.warn("Failed to resolve input mapping {} for stage {}: {}",
                        sourceKey, stage.getId(), e.getMessage());
            }
        }
    }

    /**
     * Persist resume state both in-memory and to Neo4j ExecutionRun so that
     * paused pipelines survive server restarts.
     */
    private void persistResumeState(String schemaId, String runId, int resumeIndex) {
        statusManager.getPipelineResumeState().put(schemaId, resumeIndex);
        executionRepository.updateRunResumeIndexOnly(runId, resumeIndex);
    }

    /**
     * Core stage execution — used by runPipelineStages, retryPipeline, and resumePipeline.
     * Handles: input mapping resolution, node execution with timeout, result storage,
     * AWAITING_APPROVAL detection, stage status persistence, and WS event dispatch.
     *
     * @return COMPLETED if the stage ran to completion, PAUSED if it needs approval, FAILED on error.
     */
    private StageRunResult executeSingleStage(Stage stage, WorkflowSchema schema, String runId,
                                               Map<String, Node> nodeMap, AtomicBoolean cancelFlag,
                                               String schemaId, int stageIndex, boolean skipApprovalCheck) {
        AtomicReference<Node> existingNodeRef = new AtomicReference<>(nodeMap.get(stage.getId()));
        AtomicReference<Node> scratchRef = new AtomicReference<>();

        try {
            executionRepository.updateRunStageStatus(runId, stage.getId(), "running");

            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schema.getId(), "info",
                        "Stage started: " + stage.getName(), stage.getId());
                webSocketHandler.sendLiveUpdate(schema.getId(), "pipeline_stage_started",
                        Map.of("stageId", stage.getId(), "name", stage.getName(), "status", "running"));
            }

            String resolvedModel = resolveStageModel(stage, schema);

            // Execute node with timeout
            Runnable executionTask = () -> {
                if (existingNodeRef.get() != null && existingNodeRef.get().getData() != null) {
                    Node.NodeData nd = existingNodeRef.get().getData();
                    // Sync stage prompts to blueprint node before execution
                    if (stage.getSystemPrompt() != null && !stage.getSystemPrompt().isBlank()) {
                        nd.setSystemPrompt(stage.getSystemPrompt());
                    }
                    if (stage.getUserPrompt() != null && !stage.getUserPrompt().isBlank()) {
                        nd.setUserPrompt(stage.getUserPrompt());
                    }
                    if (stage.getConfig() != null) {
                        Map<String, Object> merged = new HashMap<>();
                        if (nd.getConfig() != null) merged.putAll(nd.getConfig());
                        merged.putAll(stage.getConfig());
                        nd.setConfig(merged);
                        // Extract enabledTools from stage config if present
                        Object tools = stage.getConfig().get("enabledTools");
                        if (tools instanceof List) {
                            try {
                                @SuppressWarnings("unchecked")
                                List<String> toolList = (List<String>) tools;
                                nd.setEnabledTools(toolList);
                            } catch (Exception e) {
                                log.warn("Could not set enabledTools from stage config: {}", e.getMessage());
                            }
                        }
                    } else if (nd.getConfig() == null && "source".equals(stage.getNodeType())) {
                        nd.setConfig(new HashMap<>());
                    }
                    resolveInputMappings(stage, nd, schemaId);
                    nodeExecutor.executeNode(existingNodeRef.get(), schemaId, cancelFlag,
                            ExecutionMode.EXECUTE, resolvedModel);
                } else {
                    Node s = stageToScratchNode(stage, schemaId);
                    scratchRef.set(s);
                    resolveInputMappings(stage, s.getData(), schemaId);
                    nodeExecutor.executeNode(s, schemaId, cancelFlag,
                            ExecutionMode.EXECUTE, resolvedModel);
                }
            };

            // Determine stage timeout: node config timeoutSeconds (if set) else STAGE_TIMEOUT (20 min)
            long stageTimeoutMs = STAGE_TIMEOUT.toMillis();
            Node nodeForTimeout = existingNodeRef.get() != null ? existingNodeRef.get() : scratchRef.get();
            if (nodeForTimeout != null && nodeForTimeout.getData() != null) {
                // Priority: data.timeoutSeconds > data.config.timeoutSeconds > default 300 > STAGE_TIMEOUT
                Integer dataTimeout = nodeForTimeout.getData().getTimeoutSeconds();
                if (dataTimeout != null) {
                    stageTimeoutMs = dataTimeout * 1000L;
                } else if (nodeForTimeout.getData().getConfig() != null) {
                    Object val = nodeForTimeout.getData().getConfig().get("timeoutSeconds");
                    if (val instanceof Number) {
                        stageTimeoutMs = ((Number) val).intValue() * 1000L;
                    }
                }
            }
            try {
                CompletableFuture.runAsync(executionTask, pipelineExecutor)
                        .orTimeout(stageTimeoutMs, TimeUnit.MILLISECONDS)
                        .join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof TimeoutException) {
                    throw new RuntimeException("Stage timed out after " + (stageTimeoutMs / 1000) + "s: "
                            + stage.getName(), e.getCause());
                }
                throw e;
            }

            storeStageResult(schemaId, runId, stage.getId(), existingNodeRef.get(), scratchRef.get());

            Node executedNode = existingNodeRef.get() != null ? existingNodeRef.get() : scratchRef.get();
            if (!skipApprovalCheck && executedNode != null
                    && "review".equals(executedNode.getType())
                    && executedNode.getStatus() == Node.NodeStatus.AWAITING_APPROVAL) {
                executionRepository.updateRunStageStatus(runId, stage.getId(), "paused");
                log.info("Pipeline paused at stage {} awaiting approval", stage.getId());
                executionRepository.updateRunStatus(runId, "paused",
                        "Awaiting review approval for " + stage.getId());
                persistResumeState(schemaId, runId, stageIndex);
                if (webSocketHandler != null) {
                    webSocketHandler.sendLiveUpdate(schema.getId(), "pipeline_paused",
                            Map.of("stageId", stage.getId(), "status", "paused",
                                    "reason", "awaiting_approval"));
                }
                return StageRunResult.PAUSED;
            }

            // Pending diff review check — if agent modified existing files with requireDiffReview
            if (executedNode != null && "agent".equals(executedNode.getType())) {
                List<ExecutionStateManager.PendingDiff> pendingDiffs = stateManager.getPendingDiffs(schemaId, executedNode.getId());
                if (!pendingDiffs.isEmpty()) {
                    List<Map<String, Object>> diffPayloads = diffService.computeDiffPayloads(pendingDiffs);

                    // Store runId and nodeId for approve/reject
                    String diffKey = schemaId + ":" + executedNode.getId();
                    stateManager.putGeneratedFile("_diffRun:" + diffKey, runId);

                    executionRepository.updateRunStageStatus(runId, stage.getId(), "paused");
                    log.info("Pipeline paused at stage {} with {} diffs for review", stage.getId(), pendingDiffs.size());
                    executionRepository.updateRunStatus(runId, "paused",
                            "Awaiting diff review for " + stage.getId() + " (" + pendingDiffs.size() + " files)");
                    persistResumeState(schemaId, runId, stageIndex);
                    if (webSocketHandler != null) {
                        webSocketHandler.sendDiffsNeeded(schema.getId(), executedNode.getId(), diffPayloads);
                    }
                    return StageRunResult.PAUSED;
                }
            }

            executionRepository.updateRunStageStatus(runId, stage.getId(), "completed");

            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schema.getId(), "success",
                        "Stage completed: " + stage.getName(), stage.getId());
                webSocketHandler.sendLiveUpdate(schema.getId(), "pipeline_stage_completed",
                        Map.of("stageId", stage.getId(), "status", "completed"));
            }

            return StageRunResult.COMPLETED;
        } catch (Exception e) {
            ExecutionError error = ExecutionError.fromException(e, "Unknown error in stage " + stage.getName());
            executionRepository.updateRunStageStatus(runId, stage.getId(), "failed");
            log.error("Stage {} failed: {} ({})", stage.getName(), error.getMessage(), error.getType(), e);
            if (webSocketHandler != null) {
                webSocketHandler.sendError(schema.getId(), stage.getId(),
                        "Stage failed: " + stage.getName() + " - " + error.getMessage());
                webSocketHandler.sendLiveUpdate(schema.getId(), "pipeline_stage_failed",
                        Map.of("stageId", stage.getId(), "status", "failed",
                               "error", error.getMessage(), "errorType", error.getType()));
            }
            return StageRunResult.FAILED;
        }
    }

    /**
     * Store stage execution result in the in-memory stageResults map and persist
     * to Neo4j for downstream input mapping resolution.
     */
    private void storeStageResult(String schemaId, String runId, String stageNodeId,
                                   Node existingNode, Node scratchNode) {
        Node executed = existingNode != null ? existingNode : scratchNode;
        if (executed == null) return;

        String result = executed.getData() != null && executed.getData().getResult() != null
                ? executed.getData().getResult() : "";

        statusManager.getStageResults().computeIfAbsent(schemaId, k -> new ConcurrentHashMap<>())
                .put(stageNodeId, result);

        if (result != null && !result.isEmpty()) {
            executionRepository.updateRunStageOutput(runId, stageNodeId, result);
        }
    }

    public static Pipeline createDefaultPipeline(String appType, String description) {
        Pipeline pipeline = new Pipeline();
        pipeline.setId("default-pipeline");
        pipeline.setName("Default Pipeline");
        pipeline.setDescription("Default 9-stage pipeline with draft phases for " + appType);
        pipeline.setParallelStrategy("sequential");
        pipeline.setTddEnabled(false);

        List<Stage> stages = new ArrayList<>();

        Stage source = new Stage();
        source.setId("receive-1");
        source.setName("Receive");
        source.setNodeType("source");
        source.setSystemPrompt("Receive and process input for: " + description);
        source.setPositionX(50);
        source.setPositionY(200);
        stages.add(source);

        // ── Draft stages (spec → plan → ui → backend) ──
        Stage draftSpec = new Stage();
        draftSpec.setId("draft-spec");
        draftSpec.setName("Draft Spec");
        draftSpec.setNodeType("draft");
        draftSpec.setDependencies(List.of("receive-1"));
        draftSpec.setSystemPrompt("Generate a specification document for: " + description);
        draftSpec.setPositionX(350);
        draftSpec.setPositionY(200);
        Map<String, Object> draftSpecConfig = new HashMap<>();
        draftSpecConfig.put("draftType", "spec");
        draftSpec.setConfig(draftSpecConfig);
        stages.add(draftSpec);

        Stage draftPlan = new Stage();
        draftPlan.setId("draft-plan");
        draftPlan.setName("Draft Plan");
        draftPlan.setNodeType("draft");
        draftPlan.setDependencies(List.of("draft-spec"));
        draftPlan.setSystemPrompt("Generate an implementation plan from the spec for: " + description);
        draftPlan.setPositionX(550);
        draftPlan.setPositionY(200);
        Map<String, Object> draftPlanConfig = new HashMap<>();
        draftPlanConfig.put("draftType", "plan");
        draftPlan.setConfig(draftPlanConfig);
        stages.add(draftPlan);

        Stage draftUi = new Stage();
        draftUi.setId("draft-ui");
        draftUi.setName("Draft UI");
        draftUi.setNodeType("draft");
        draftUi.setDependencies(List.of("draft-plan"));
        draftUi.setSystemPrompt("Generate an OpenUI YAML spec from the plan for: " + description);
        draftUi.setPositionX(750);
        draftUi.setPositionY(200);
        Map<String, Object> draftUiConfig = new HashMap<>();
        draftUiConfig.put("draftType", "ui");
        draftUi.setConfig(draftUiConfig);
        stages.add(draftUi);

        Stage draftBackend = new Stage();
        draftBackend.setId("draft-backend");
        draftBackend.setName("Draft Backend");
        draftBackend.setNodeType("draft");
        draftBackend.setDependencies(List.of("draft-ui"));
        draftBackend.setSystemPrompt("Generate backend module architecture from the UI spec for: " + description);
        draftBackend.setPositionX(950);
        draftBackend.setPositionY(200);
        Map<String, Object> draftBackendConfig = new HashMap<>();
        draftBackendConfig.put("draftType", "backend");
        draftBackend.setConfig(draftBackendConfig);
        stages.add(draftBackend);

        Stage review = new Stage();
        review.setId("review-1");
        review.setName("Review Plan");
        review.setNodeType("review");
        review.setDependencies(List.of("draft-backend"));
        review.setSystemPrompt("Review the plan for: " + description);
        review.setPositionX(1150);
        review.setPositionY(200);
        stages.add(review);

        Stage agent = new Stage();
        agent.setId("think-1");
        agent.setName("Execute");
        agent.setNodeType("agent");
        agent.setDependencies(List.of("review-1"));
        agent.setSystemPrompt("Execute the plan for: " + description);
        agent.setPositionX(1450);
        agent.setPositionY(200);
        stages.add(agent);

        Stage verifier = new Stage();
        verifier.setId("verify-1");
        verifier.setName("Verify");
        verifier.setNodeType("verifier");
        verifier.setDependencies(List.of("think-1"));
        verifier.setSystemPrompt("Verify the results for: " + description);
        verifier.setPositionX(1750);
        verifier.setPositionY(200);
        stages.add(verifier);

        Stage output = new Stage();
        output.setId("act-1");
        output.setName("Output");
        output.setNodeType("output");
        output.setDependencies(List.of("verify-1"));
        output.setSystemPrompt("Output the results for: " + description);
        output.setPositionX(2050);
        output.setPositionY(200);
        stages.add(output);

        pipeline.setStages(stages);
        return pipeline;
    }

    /**
     * When {@code pipeline.tddEnabled == true}, expands each agent → verifier stage pair
     * into a 4-stage TDD block: test → verify-test → impl → verify.
     * <p>
     * Dependencies: verify-test depends on test; impl depends on test (not verify-test);
     * verify depends on impl. This enables parallel execution of verify-test and impl
     * once test completes.
     * <p>
     * Stages that depended on the original agent are rewritten to depend on impl;
     * stages that depended on the original verifier are rewritten to depend on the new verify.
     * <p>
     * No-op when {@code tddEnabled == false} or when no agent → verifier pairs are found.
     */
    public static void expandTddStages(Pipeline pipeline) {
        if (pipeline == null || !pipeline.isTddEnabled()) return;

        List<Stage> stages = new ArrayList<>(pipeline.getStages());
        List<Stage> expanded = new ArrayList<>();
        Map<String, String> depRewrites = new HashMap<>();
        Set<String> skipIds = new HashSet<>();

        for (Stage s : stages) {
            if (skipIds.contains(s.getId())) continue;

            Stage verifier = findVerifierForAgent(stages, s);

            if (verifier != null) {
                String x = s.getId(); // e.g., "think-1"

                // test-X — same deps as the original agent (e.g., depends on review)
                Stage test = new Stage();
                test.setId("test-" + x);
                test.setName("Write Tests");
                test.setNodeType("agent");
                test.setDependencies(s.getDependencies() != null
                        ? new ArrayList<>(s.getDependencies()) : null);
                test.setSystemPrompt("Write tests for the planned implementation. "
                        + "Cover edge cases, normal operation, and expected failures.\n"
                        + (s.getSystemPrompt() != null ? s.getSystemPrompt() : ""));
                test.setModel(s.getModel());
                test.setConfig(s.getConfig() != null ? new HashMap<>(s.getConfig()) : null);
                test.setPositionX(s.getPositionX());
                test.setPositionY(s.getPositionY());

                // verify-test-X — depends on test-X, receives test output as context
                Stage verifyTest = new Stage();
                verifyTest.setId("verify-test-" + x);
                verifyTest.setName("Verify Tests");
                verifyTest.setNodeType("verifier");
                verifyTest.setDependencies(List.of(test.getId()));
                verifyTest.setSystemPrompt("Verify that the tests are correctly written, "
                        + "are executable, and cover the planned functionality. "
                        + "Run them to confirm the test harness works.\n"
                        + "Tests written by upstream stage: {{upstreamOutput}}");
                verifyTest.setModel(s.getModel());
                verifyTest.setInputMapping(new HashMap<>(Map.of(
                        test.getId(), "upstreamOutput"
                )));
                verifyTest.setPositionX(s.getPositionX() + 200);
                verifyTest.setPositionY(s.getPositionY());

                // impl-X — replaces original agent, depends on test-X only (not verify-test-X)
                // Receives test output so implementation satisfies the written tests
                Stage impl = new Stage();
                impl.setId("impl-" + x);
                impl.setName("Implement");
                impl.setNodeType("agent");
                impl.setDependencies(List.of(test.getId()));
                impl.setSystemPrompt("Implement the planned functionality. "
                        + "Write code that passes the tests written in the previous stage.\n"
                        + (s.getSystemPrompt() != null ? s.getSystemPrompt() : "")
                        + "\nTests to satisfy: {{upstreamOutput}}");
                impl.setModel(s.getModel());
                impl.setConfig(s.getConfig() != null ? new HashMap<>(s.getConfig()) : null);
                impl.setInputMapping(new HashMap<>(Map.of(
                        test.getId(), "upstreamOutput"
                )));
                impl.setPositionX(s.getPositionX() + 400);
                impl.setPositionY(s.getPositionY());

                // verify-X — replaces original verifier, depends on impl-X
                // Receives impl output so it knows what implementation was written
                Stage verify = new Stage();
                verify.setId("verify-" + x);
                verify.setName("Verify Implementation");
                verify.setNodeType("verifier");
                verify.setDependencies(List.of(impl.getId()));
                verify.setSystemPrompt("Verify the implementation against the requirements. "
                        + "Run the tests to confirm everything passes.\n"
                        + (verifier.getSystemPrompt() != null ? verifier.getSystemPrompt() : "")
                        + "\nImplementation to verify: {{upstreamOutput}}");
                verify.setModel(verifier.getModel());
                verify.setInputMapping(new HashMap<>(Map.of(
                        impl.getId(), "upstreamOutput"
                )));
                verify.setPositionX(s.getPositionX() + 600);
                verify.setPositionY(s.getPositionY());

                expanded.add(test);
                expanded.add(verifyTest);
                expanded.add(impl);
                expanded.add(verify);

                // Rewrite downstream dependency references: old IDs → new
                depRewrites.put(s.getId(), impl.getId());       // old agent → new impl
                depRewrites.put(verifier.getId(), verify.getId()); // old verifier → new verify

                skipIds.add(verifier.getId());
            } else {
                expanded.add(s);
            }
        }

        // Fix up any downstream stages that referenced the replaced stage IDs
        for (Stage stage : expanded) {
            if (stage.getDependencies() != null) {
                List<String> updated = new ArrayList<>();
                for (String dep : stage.getDependencies()) {
                    updated.add(depRewrites.getOrDefault(dep, dep));
                }
                stage.setDependencies(updated);
            }
        }

        pipeline.setStages(expanded);
        log.info("expandTddStages: expanded {} stages to {} stages (tddEnabled=true)",
                stages.size(), expanded.size());
    }

    /**
     * Finds the first verifier stage whose dependencies include the given agent stage's ID.
     * Returns null if the given stage is not an agent or no matching verifier is found.
     */
    private static Stage findVerifierForAgent(List<Stage> stages, Stage agent) {
        if (!"agent".equals(agent.getNodeType())) return null;
        for (Stage s : stages) {
            if ("verifier".equals(s.getNodeType())
                    && s.getDependencies() != null
                    && s.getDependencies().contains(agent.getId())) {
                return s;
            }
        }
        return null;
    }

    /**
     * Initialize the stage status map for an ExecutionRun.
     */
    private static void initializeRunStageStatus(ExecutionRun run, List<Stage> stages) {
        Map<String, String> initStatus = new HashMap<>();
        for (Stage s : stages) {
            initStatus.put(s.getId(), "pending");
        }
        run.setStageStatus(initStatus);
    }

    /**
     * Create Stage objects from the schema's canvas nodes/edges.
     * Each canvas node becomes a Stage with the same ID, name, model, systemPrompt, nodeType, and config.
     * Dependencies are derived from edges (source → target).
     */
    static List<Stage> createStagesFromNodes(WorkflowSchema schema) {
        List<Stage> stages = new ArrayList<>();
        if (schema.getNodes() == null || schema.getNodes().isEmpty()) return stages;

        // Build adjacency: nodeId → list of source node IDs (dependencies)
        Map<String, List<String>> depMap = new HashMap<>();
        if (schema.getEdges() != null) {
            for (Edge edge : schema.getEdges()) {
                if (edge.getSource() != null && edge.getTarget() != null) {
                    depMap.computeIfAbsent(edge.getTarget(), k -> new ArrayList<>()).add(edge.getSource());
                }
            }
        }

        for (Node node : schema.getNodes()) {
            Stage stage = new Stage();
            stage.setId(node.getId());
            stage.setName(node.getName() != null ? node.getName() : node.getType() + "-" + node.getId().substring(0, 8));
            stage.setNodeType(node.getType());

            // Copy model from node data if set
            if (node.getData() != null && node.getData().getModel() != null) {
                stage.setModel(node.getData().getModel());
            }

            // Copy systemPrompt from node data
            if (node.getData() != null && node.getData().getSystemPrompt() != null) {
                stage.setSystemPrompt(node.getData().getSystemPrompt());
            }

            // Copy config into stage config
            if (node.getData() != null && node.getData().getConfig() != null) {
                stage.setConfig(new HashMap<>(node.getData().getConfig()));
            }

            // Set dependencies from edge adjacency
            List<String> deps = depMap.getOrDefault(node.getId(), List.of());
            if (!deps.isEmpty()) {
                stage.setDependencies(new ArrayList<>(deps));
            }

            stages.add(stage);
        }

        return stages;
    }
}
