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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    private final Neo4jSchemaRepository schemaRepository;
    private final NodeExecutor nodeExecutor;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final ExecutionRepository executionRepository;
    private final ExecutorService pipelineExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final ConcurrentHashMap<String, CompletableFuture<?>> runningPipelines = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> stageResults = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> pipelineResumeState = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Per-stage timeout — 5 minutes default. Stages exceeding this are failed. */
    private static final Duration STAGE_TIMEOUT = Duration.ofMinutes(20);

    enum StageRunResult { COMPLETED, PAUSED, FAILED }

    public PipelineService(Neo4jSchemaRepository schemaRepository,
                           NodeExecutor nodeExecutor,
                           ExecutionWebSocketHandler webSocketHandler,
                           ExecutionRepository executionRepository) {
        this.schemaRepository = schemaRepository;
        this.nodeExecutor = nodeExecutor;
        this.webSocketHandler = webSocketHandler;
        this.executionRepository = executionRepository;
    }

    public WorkflowSchema buildPipelineNodes(String schemaId) {
        WorkflowSchema schema = schemaRepository.findById(schemaId);
        if (schema == null) {
            throw new RuntimeException("Schema not found: " + schemaId);
        }
        if (schema.getPipeline() == null || schema.getPipeline().getStages() == null) {
            throw new RuntimeException("Schema has no pipeline definition");
        }

        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        Map<String, String> stageToNode = new HashMap<>();

        int i = 0;
        for (Stage stage : schema.getPipeline().getStages()) {
            String nodeId = stage.getId() != null ? stage.getId() : "stage-" + UUID.randomUUID().toString().substring(0, 8);

            Node node = new Node();
            node.setId(nodeId);
            node.setType(stage.getNodeType() != null ? stage.getNodeType() : "agent");
            node.setName(stage.getName() != null ? stage.getName() : "Stage " + (i + 1));
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
            if (stage.getLoopCondition() != null) {
                data.setLoopCondition(stage.getLoopCondition());
                data.setMaxIterations(stage.getMaxIterations());
            }

            node.setData(data);

            Node.Position pos = new Node.Position();
            pos.setX(stage.getPositionX() != 0 ? stage.getPositionX() : 100 + (i * 300));
            pos.setY(stage.getPositionY() != 0 ? stage.getPositionY() : 200);
            node.setPosition(pos);

            node.setInputPorts(List.of("in"));
            node.setOutputPorts(List.of("out"));

            nodes.add(node);
            stageToNode.put(stage.getId(), nodeId);
            i++;
        }

        for (Stage stage : schema.getPipeline().getStages()) {
            if (stage.getDependencies() != null) {
                String targetId = stageToNode.get(stage.getId());
                for (String depId : stage.getDependencies()) {
                    String sourceId = stageToNode.get(depId);
                    if (sourceId != null && targetId != null) {
                        Edge edge = new Edge();
                        edge.setId("e-" + sourceId + "-" + targetId);
                        edge.setSource(sourceId);
                        edge.setTarget(targetId);
                        edge.setSourcePort("out");
                        edge.setTargetPort("in");
                        edges.add(edge);
                    }
                }
            }
        }

        schema.setNodes(nodes);
        schema.setEdges(edges);
        schemaRepository.save(schema);
        log.info("Built {} nodes and {} edges from pipeline for schema {}", nodes.size(), edges.size(), schemaId);
        return schema;
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

        CompletableFuture<?> existing = runningPipelines.get(schemaId);
        if (existing != null && !existing.isDone()) {
            log.warn("Pipeline already running: {}", schemaId);
            return;
        }

        String runId = UUID.randomUUID().toString();
        ExecutionRun run = new ExecutionRun();
        run.setId(runId);
        run.setSchemaId(schemaId);
        run.setStatus("running");
        run.setMode("PIPELINE");
        run.setStartedAt(Instant.now().toString());
        run.setUpdatedAt(Instant.now().toString());
        // Initialize stage status map
        if (schema.getPipeline() != null && schema.getPipeline().getStages() != null) {
            Map<String, String> initStatus = new HashMap<>();
            for (Stage s : schema.getPipeline().getStages()) {
                initStatus.put(s.getId(), "pending");
            }
            run.setStageStatus(initStatus);
        }
        executionRepository.createRun(run);

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        cancelFlags.put(schemaId, cancelFlag);
        stageResults.put(schemaId, new ConcurrentHashMap<>());

        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "info", "Pipeline execution started: " + schema.getName(), null);
            webSocketHandler.sendProgress(schemaId, "system", "PIPELINE_STARTED", 0, "Pipeline started");
        }

        CompletableFuture<?> future = CompletableFuture.runAsync(
                () -> runPipelineStages(schema, runId, cancelFlag), pipelineExecutor);
        runningPipelines.put(schemaId, future);

        future.whenComplete((result, ex) -> {
            runningPipelines.remove(schemaId);
            cancelFlags.remove(schemaId);
            // Note: stageResults NOT removed here to avoid race with
            // retryPipeline/resumePipeline which share the schemaId key.
            // stageResults is overwritten on next execute/retry/resume.
            if (ex != null) {
                if (ex instanceof CancellationException || ex.getCause() instanceof CancellationException) {
                    log.info("Pipeline cancelled for schema {}", schemaId);
                    executionRepository.updateRunStatus(runId, "cancelled", "Cancelled by user");
                } else {
                    log.error("Pipeline execution failed for {}: {}", schemaId, ex.getMessage(), ex);
                    executionRepository.updateRunStatus(runId, "failed", ex.getMessage());
                }
            }
        });
    }

    private void runPipelineStages(WorkflowSchema schema, String runId, AtomicBoolean cancelFlag) {
        Pipeline pipeline = schema.getPipeline();
        List<Stage> stages = pipeline.getStages();
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

            boolean paused = false;
            boolean hasFailure = false;
            int stageIndex = completedStages;
            for (Stage stage : level) {
                if (cancelFlag.get() || paused || hasFailure) break;

                StageRunResult result = executeSingleStage(stage, schema, runId, nodeMap,
                        cancelFlag, schema.getId(), stageIndex, false);
                stageIndex++;
                if (result == StageRunResult.PAUSED) {
                    paused = true;
                    break;
                } else if (result == StageRunResult.FAILED) {
                    hasFailure = true;
                    // Continue to next stage in the level
                }
                // COMPLETED: continue to next
            }

            if (paused) break;

            completedStages += level.size();
            int progress = (int) ((double) completedStages / totalStages * 100);
            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schema.getId(), "system", "PIPELINE_PROGRESS",
                        progress, completedStages + "/" + totalStages + " stages completed");
            }
        }

        boolean isPaused = pipelineResumeState.containsKey(schema.getId());

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
        }
    }

    public void retryPipeline(String schemaId) {
        WorkflowSchema schema = schemaRepository.findById(schemaId);
        if (schema == null) {
            throw new RuntimeException("Schema not found: " + schemaId);
        }

        ExecutionRun failedRun = executionRepository.getLatestRunBySchemaAndStatus(schemaId, "failed");
        if (failedRun == null) {
            throw new RuntimeException("No failed pipeline run found for schema " + schemaId);
        }

        Pipeline pipeline = schema.getPipeline();
        List<Stage> stages = pipeline.getStages();
        if (stages == null || stages.isEmpty()) {
            throw new RuntimeException("Pipeline has no stages to retry");
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
        run.setMode("PIPELINE");
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
        cancelFlags.put(schemaId, cancelFlag);
        stageResults.put(schemaId, new ConcurrentHashMap<>());

        // Re-run stages from first failed onwards — same logic as inner loop of runPipelineStages
        Map<String, Node> nodeMap = new HashMap<>();
        if (schema.getNodes() != null) {
            for (Node n : schema.getNodes()) nodeMap.put(n.getId(), cloneNode(n));
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
                    stageResults.get(schemaId).put(completedId, output);
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

                StageRunResult result = executeSingleStage(stage, schema, runId, nodeMap,
                        cancelFlag, schemaId, retryStageIndex, true);
                retryStageIndex++;
                if (result == StageRunResult.FAILED) {
                    executionRepository.updateRunCompleted(runId, "failed", 0, 0.0);
                    cancelFlags.remove(schemaId);
                    stageResults.remove(schemaId);
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

        cancelFlags.remove(schemaId);
        stageResults.remove(schemaId);
    }

    public void resumePipeline(String schemaId) {
        WorkflowSchema schema = schemaRepository.findById(schemaId);
        if (schema == null) {
            throw new RuntimeException("Schema not found: " + schemaId);
        }

        Integer resumeIndex = pipelineResumeState.remove(schemaId);
        ExecutionRun run = null;

        if (resumeIndex == null) {
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

        Pipeline pipeline = schema.getPipeline();
        List<Stage> stages = pipeline.getStages();
        if (stages == null || stages.isEmpty() || resumeIndex >= stages.size()) {
            log.warn("No stages to resume for schema {}", schemaId);
            return;
        }

        // Mark run as running again
        executionRepository.updateRunStatus(run.getId(), "running", null);

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
        cancelFlags.put(schemaId, cancelFlag);
        stageResults.put(schemaId, new ConcurrentHashMap<>());

        // Pre-populate stageResults with completed stages' outputs (from the initial run that paused)
        if (run.getStageOutputs() != null) {
            for (String completedId : completedStageIds) {
                String output = run.getStageOutputs().get(completedId);
                if (output != null) {
                    stageResults.get(schemaId).put(completedId, output);
                }
            }
        }

        boolean resumedPaused = false;
        int resumeStageIndex = resumeIndex;
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
        AtomicBoolean flag = cancelFlags.get(schemaId);
        if (flag != null) flag.set(true);
        CompletableFuture<?> future = runningPipelines.get(schemaId);
        if (future != null) future.cancel(true);
    }

    public Map<String, String> getStageResults(String schemaId) {
        return stageResults.getOrDefault(schemaId, new ConcurrentHashMap<>());
    }

    public boolean isPipelineRunning(String schemaId) {
        CompletableFuture<?> future = runningPipelines.get(schemaId);
        return future != null && !future.isDone();
    }

    /**
     * Resolve input mappings for a stage by pulling output fields from upstream stages
     * stored in stageResults. Handles both "sourceStageId.fieldName" (JSON field extraction)
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

        Map<String, String> results = stageResults.get(schemaId);
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
        pipelineResumeState.put(schemaId, resumeIndex);
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
                // Resolve input mappings from upstream stages
                if (existingNodeRef.get() != null && existingNodeRef.get().getData() != null) {
                    resolveInputMappings(stage, existingNodeRef.get().getData(), schemaId);
                }

                if (existingNodeRef.get() != null) {
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

            try {
                CompletableFuture.runAsync(executionTask, pipelineExecutor)
                        .orTimeout(STAGE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof TimeoutException) {
                    throw new RuntimeException("Stage timed out after " + STAGE_TIMEOUT.getSeconds() + "s: "
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

            executionRepository.updateRunStageStatus(runId, stage.getId(), "completed");

            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schema.getId(), "success",
                        "Stage completed: " + stage.getName(), stage.getId());
                webSocketHandler.sendLiveUpdate(schema.getId(), "pipeline_stage_completed",
                        Map.of("stageId", stage.getId(), "status", "completed"));
            }

            return StageRunResult.COMPLETED;
        } catch (Exception e) {
            executionRepository.updateRunStageStatus(runId, stage.getId(), "failed");
            log.error("Stage {} failed: {}", stage.getName(), e.getMessage(), e);
            if (webSocketHandler != null) {
                webSocketHandler.sendError(schema.getId(), stage.getId(),
                        "Stage failed: " + stage.getName() + " - " + e.getMessage());
                webSocketHandler.sendLiveUpdate(schema.getId(), "pipeline_stage_failed",
                        Map.of("stageId", stage.getId(), "status", "failed", "error", e.getMessage()));
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

        stageResults.computeIfAbsent(schemaId, k -> new ConcurrentHashMap<>())
                .put(stageNodeId, result);

        if (result != null && !result.isEmpty()) {
            executionRepository.updateRunStageOutput(runId, stageNodeId, result);
        }
    }

    public static Pipeline createDefaultPipeline(String appType, String description) {
        Pipeline pipeline = new Pipeline();
        pipeline.setId("default-pipeline");
        pipeline.setName("Default Pipeline");
        pipeline.setDescription("Default 5-stage pipeline for " + appType);
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

        Stage review = new Stage();
        review.setId("review-1");
        review.setName("Review Plan");
        review.setNodeType("review");
        review.setDependencies(List.of("receive-1"));
        review.setSystemPrompt("Review the plan for: " + description);
        review.setPositionX(350);
        review.setPositionY(200);
        stages.add(review);

        Stage agent = new Stage();
        agent.setId("think-1");
        agent.setName("Execute");
        agent.setNodeType("agent");
        agent.setDependencies(List.of("review-1"));
        agent.setSystemPrompt("Execute the plan for: " + description);
        agent.setPositionX(650);
        agent.setPositionY(200);
        stages.add(agent);

        Stage verifier = new Stage();
        verifier.setId("verify-1");
        verifier.setName("Verify");
        verifier.setNodeType("verifier");
        verifier.setDependencies(List.of("think-1"));
        verifier.setSystemPrompt("Verify the results for: " + description);
        verifier.setPositionX(950);
        verifier.setPositionY(200);
        stages.add(verifier);

        Stage output = new Stage();
        output.setId("act-1");
        output.setName("Output");
        output.setNodeType("output");
        output.setDependencies(List.of("verify-1"));
        output.setSystemPrompt("Output the results for: " + description);
        output.setPositionX(1250);
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
}
