package com.agent.orchestrator.service;

import com.agent.orchestrator.model.*;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Extracted stage execution logic from PipelineServiceImpl.
 * Handles retry, resume, and single-stage execution for pipeline workflows.
 */
@Service
public class PipelineStageExecutionService {

    private static final Logger log = LoggerFactory.getLogger(PipelineStageExecutionService.class);

    private final Neo4jSchemaRepository schemaRepository;
    private final NodeRouter nodeRouter;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final ExecutionRepository executionRepository;
    private final ExecutionStateManager stateManager;
    private final PipelineStatusManager statusManager;
    private final PlanService planService;
    private final PipelineStageRunner stageRunner;
    private final ObjectMapper mapper = new ObjectMapper();

    public PipelineStageExecutionService(Neo4jSchemaRepository schemaRepository,
                                          NodeRouter nodeRouter,
                                          ExecutionWebSocketHandler webSocketHandler,
                                          ExecutionRepository executionRepository,
                                          ExecutionStateManager stateManager,
                                          PipelineStatusManager statusManager,
                                          PlanService planService,
                                          PipelineStageRunner stageRunner) {
        this.schemaRepository = schemaRepository;
        this.nodeRouter = nodeRouter;
        this.webSocketHandler = webSocketHandler;
        this.executionRepository = executionRepository;
        this.stateManager = stateManager;
        this.statusManager = statusManager;
        this.planService = planService;
        this.stageRunner = stageRunner;
    }

    void clearStaleApprovals(String schemaId) {
        statusManager.clearStaleApprovals(schemaId, stateManager != null ? stateManager.getNodeResults() : null);
    }

    // ── Core pipeline stage runner (extracted from PipelineServiceImpl.runStages) ──

    public void runPipelineStages(List<Stage> stages, WorkflowSchema schema, String runId, AtomicBoolean cancelFlag) {
        if (stages == null || stages.isEmpty()) return;

        // ── Create plan task for this session ──
        String sessionTaskId = null;
        if (schema.getWorkspaceId() != null && !schema.getWorkspaceId().isBlank()) {
            try {
                List<ExecutionRun> completedRuns = executionRepository.getCompletedRuns(schema.getId(), 10);
                int sessionNum = completedRuns.size() + 1;
                String taskTitle = "Session " + sessionNum + ": " + schema.getName();
                Task task = planService.addTask(schema.getWorkspaceId(), taskTitle, schema.getDescription(),
                        null, null, null, schema.getId());
                sessionTaskId = task.getId();
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schema.getId(), "info",
                            "Plan task created: " + taskTitle, null);
                }
            } catch (Exception e) {
                log.warn("Failed to create plan task for schema {}: {}", schema.getId(), e.getMessage());
            }
        }

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

            // Run stages within a level concurrently
            @SuppressWarnings("unchecked")
            CompletableFuture<Void>[] futures = new CompletableFuture[level.size()];
            boolean[] levelPaused = { false };
            boolean[] levelFailed = { false };

            for (int si = 0; si < level.size(); si++) {
                Stage stage = level.get(si);
                int stageIdx = completedStages + si;
                futures[si] = CompletableFuture.runAsync(() -> {
                    if (cancelFlag.get() || levelPaused[0] || levelFailed[0]) return;
                    PipelineStageRunner.StageRunResult result = stageRunner.executeStage(stage, schema, runId, nodeMap,
                            cancelFlag, schema.getId(), stageIdx, false);
                    if (result == PipelineStageRunner.StageRunResult.PAUSED) {
                        synchronized (levelPaused) { levelPaused[0] = true; }
                    } else if (result == PipelineStageRunner.StageRunResult.FAILED) {
                        synchronized (levelFailed) { levelFailed[0] = true; }
                    }
                });
            }
            CompletableFuture.allOf(futures).join();

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

        boolean isPaused = statusManager.hasResumeState(schema.getId());

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

            // ── Complete plan task if created ──
            if (sessionTaskId != null && !cancelFlag.get()) {
                try {
                    List<String> genFiles = new ArrayList<>();
                    Map<String, Object> filesRegistry = stateManager.getGeneratedFilesRegistry();
                    String prefix = schema.getId() + ":";
                    for (Map.Entry<String, Object> entry : filesRegistry.entrySet()) {
                        if (entry.getKey().startsWith(prefix)) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, String>> files = (List<Map<String, String>>) entry.getValue();
                            if (files != null) {
                                for (Map<String, String> f : files) {
                                    String path = f.get("path");
                                    if (path != null) genFiles.add(path);
                                }
                            }
                        }
                    }
                    List<Task.GeneratedFile> genFileList = genFiles.stream()
                            .map(p -> new Task.GeneratedFile(p, ""))
                            .toList();
                    planService.completeTaskForExecution(sessionTaskId, schema.getWorkspaceId(), genFileList);
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schema.getId(), "info",
                                "Plan task completed: " + genFiles.size() + " files", null);
                    }
                } catch (Exception e) {
                    log.warn("Failed to complete plan task {}: {}", sessionTaskId, e.getMessage());
                }
            }

            stateManager.removeSchema(schema.getId());
        }
    }

    // ── Retry ──

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
            stages = PipelineFactory.createStagesFromNodes(schema);
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
        run.setStartedAt(Instant.now());
        run.setUpdatedAt(Instant.now());

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
        statusManager.registerCancelAndResults(schemaId, cancelFlag);

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
                    statusManager.putStageResult(schemaId, completedId, output);
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
                PipelineStageRunner.StageRunResult result = stageRunner.executeStage(stage, schema, runId, nodeMap,
                        cancelFlag, schemaId, retryStageIndex, retrySkipApproval);
                retryStageIndex++;
                if (result == PipelineStageRunner.StageRunResult.FAILED) {
                    executionRepository.updateRunCompleted(runId, "failed", 0, 0.0);
                    statusManager.unregisterPipeline(schemaId);
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

        statusManager.unregisterPipeline(schemaId);
    }

    public void retryPipeline(String schemaId, String runId) {
        WorkflowSchema schema = schemaRepository.findById(schemaId);
        if (schema == null) {
            throw new RuntimeException("Schema not found: " + schemaId);
        }

        ExecutionRun failedRun = executionRepository.getRunById(runId);
        if (failedRun == null) {
            throw new RuntimeException("Execution run not found: " + runId);
        }
        if (!"failed".equals(failedRun.getStatus())) {
            throw new RuntimeException("Run " + runId + " is not in failed status");
        }

        // Derive stages from pipeline or canvas nodes
        List<Stage> stages;
        if (schema.getPipeline() != null && schema.getPipeline().getStages() != null
                && !schema.getPipeline().getStages().isEmpty()) {
            stages = schema.getPipeline().getStages();
        } else {
            stages = PipelineFactory.createStagesFromNodes(schema);
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
        String newRunId = UUID.randomUUID().toString();
        ExecutionRun run = new ExecutionRun();
        run.setId(newRunId);
        run.setSchemaId(schemaId);
        run.setStatus("running");
        run.setMode(failedRun.getMode() != null ? failedRun.getMode() : "PIPELINE");
        run.setResumesFrom(failedRun.getId());
        run.setStartedAt(Instant.now());
        run.setUpdatedAt(Instant.now());

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
        statusManager.registerCancelAndResults(schemaId, cancelFlag);

        // Re-run stages from first failed onwards
        Map<String, Node> nodeMap = new HashMap<>();
        if (schema.getNodes() != null) {
            for (Node n : schema.getNodes()) nodeMap.put(n.getId(), cloneNode(n));
        }

        // Track stages that were paused before the failure
        Set<String> pausedBeforeFailure = new HashSet<>();
        for (int i = 0; i < stages.size(); i++) {
            if (i >= firstFailedIndex) break;
            String sid = stages.get(i).getId();
            String st = persistedStatus != null ? persistedStatus.get(sid) : null;
            if ("paused".equals(st)) {
                pausedBeforeFailure.add(sid);
            }
        }

        List<Stage> retryStages = new ArrayList<>();
        Set<String> completedBefore = new HashSet<>();
        for (int i = 0; i < stages.size(); i++) {
            if (i >= firstFailedIndex) {
                Stage s = stages.get(i);
                Stage copy = copyStage(s);
                List<String> filtered = new ArrayList<>();
                if (s.getDependencies() != null) {
                    for (String dep : s.getDependencies()) {
                        String depSt = persistedStatus != null ? persistedStatus.get(dep) : null;
                        if ("completed".equals(depSt)) {
                            filtered.add(dep);
                        } else if (i < firstFailedIndex) {
                            filtered.add(dep);
                        }
                    }
                }
                copy.setDependencies(filtered);
                retryStages.add(copy);
            } else {
                completedBefore.add(stages.get(i).getId());
            }
        }

        if (failedRun.getStageOutputs() != null) {
            for (String completedId : completedBefore) {
                String output = failedRun.getStageOutputs().get(completedId);
                if (output != null) {
                    statusManager.putStageResult(schemaId, completedId, output);
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
                PipelineStageRunner.StageRunResult result = stageRunner.executeStage(stage, schema, newRunId, nodeMap,
                        cancelFlag, schemaId, retryStageIndex, retrySkipApproval);
                retryStageIndex++;
                if (result == PipelineStageRunner.StageRunResult.FAILED) {
                    executionRepository.updateRunCompleted(newRunId, "failed", 0, 0.0);
                    statusManager.unregisterPipeline(schemaId);
                    return;
                }
            }
            completedRetry += level.size();
        }

        if (cancelFlag.get()) {
            executionRepository.updateRunCompleted(newRunId, "cancelled", 0, 0.0);
        } else {
            executionRepository.updateRunCompleted(newRunId, "completed", 0, 0.0);
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

        statusManager.unregisterPipeline(schemaId);
    }

    // ── Resume ──

    public void resumePipeline(String schemaId) {
        resumePipeline(schemaId, null);
    }

    public void resumePipeline(String schemaId, String runId) {
        WorkflowSchema schema = schemaRepository.findById(schemaId);
        if (schema == null) {
            throw new RuntimeException("Schema not found: " + schemaId);
        }

        Integer resumeIndex = statusManager.consumeResumeState(schemaId);
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
            stages = PipelineFactory.createStagesFromNodes(schema);
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
                stateManager.getNodeResults()
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
        statusManager.registerCancelAndResults(schemaId, cancelFlag);

        // Pre-populate stageResults with completed stages' outputs
        if (run.getStageOutputs() != null) {
            for (String completedId : completedStageIds) {
                String output = run.getStageOutputs().get(completedId);
                if (output != null) {
                    statusManager.putStageResult(schemaId, completedId, output);
                }
            }
        }

        boolean resumedPaused = false;
        int resumeStageIndex = resumeIndex;
        try {
            executionRepository.updateRunStatus(run.getId(), "running", null);

            for (List<Stage> level : remainingLevels) {
                if (cancelFlag.get()) break;

                for (Stage stage : level) {
                    if (cancelFlag.get() || resumedPaused) break;

                    PipelineStageRunner.StageRunResult result = stageRunner.executeStage(stage, schema, run.getId(), nodeMap,
                            cancelFlag, schemaId, resumeStageIndex, false);
                    resumeStageIndex++;
                    if (result == PipelineStageRunner.StageRunResult.PAUSED) {
                        resumedPaused = true;
                        break;
                    } else if (result == PipelineStageRunner.StageRunResult.FAILED) {
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
            // Already handled — don't mark completed
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
            // Persist generated files to ExecutionRun
            Map<String, Object> genFiles = stateManager.getGeneratedFilesRegistry();
            List<String> schemaFiles = new ArrayList<>();
            String prefix = schema.getId() + ":";
            for (Map.Entry<String, Object> entry : genFiles.entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, String>> files = (List<Map<String, String>>) entry.getValue();
                    if (files != null) {
                        for (Map<String, String> f : files) {
                            String path = f.get("path");
                            if (path != null) schemaFiles.add(path);
                        }
                    }
                }
            }
            if (!schemaFiles.isEmpty()) {
                executionRepository.updateRunGeneratedFiles(run.getId(), schemaFiles);
            }
            executionRepository.updateRunCompleted(run.getId(), cancelFlag.get() ? "cancelled" : "completed", 0, 0.0);
        }
    }

    // ── Helpers ──

    List<List<Stage>> topologicalSortStages(List<Stage> stages) {
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

    Stage copyStage(Stage s) {
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

    Node cloneNode(Node original) {
        return mapper.convertValue(original, Node.class);
    }

}
