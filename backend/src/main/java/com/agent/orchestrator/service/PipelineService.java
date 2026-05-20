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

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

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
            if (ex != null && !(ex instanceof CancellationException)) {
                log.error("Pipeline execution failed for {}: {}", schemaId, ex.getMessage(), ex);
                executionRepository.updateRunStatus(runId, "failed", ex.getMessage());
            }
        });
    }

    private void runPipelineStages(WorkflowSchema schema, String runId, AtomicBoolean cancelFlag) {
        Pipeline pipeline = schema.getPipeline();
        List<Stage> stages = pipeline.getStages();
        if (stages == null || stages.isEmpty()) return;

        Map<String, Node> nodeMap = new HashMap<>();
        if (schema.getNodes() != null) {
            for (Node n : schema.getNodes()) nodeMap.put(n.getId(), n);
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
            for (Stage stage : level) {
                if (cancelFlag.get() || paused || hasFailure) break;

                String stageNodeId = stage.getId();
                Node existingNode = nodeMap.get(stageNodeId);

                Node scratch = null;
                try {
                    executionRepository.updateRunStageStatus(runId, stageNodeId, "running");

                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schema.getId(), "info",
                                "Stage started: " + stage.getName(), stageNodeId);
                        webSocketHandler.sendLiveUpdate(schema.getId(), "pipeline_stage_started",
                                Map.of("stageId", stageNodeId, "name", stage.getName(), "status", "running"));
                    }

                    String resolvedModel = resolveStageModel(stage, schema);

                    // Resolve input mappings from upstream stages
                    if (existingNode != null && existingNode.getData() != null) {
                        resolveInputMappings(stage, existingNode.getData(), schema.getId());
                    }

                    if (existingNode != null) {
                        nodeExecutor.executeNode(existingNode, schema.getId(), cancelFlag,
                                ExecutionMode.EXECUTE, resolvedModel);
                    } else {
                        scratch = stageToScratchNode(stage, schema.getId());
                        resolveInputMappings(stage, scratch.getData(), schema.getId());
                        nodeExecutor.executeNode(scratch, schema.getId(), cancelFlag,
                                ExecutionMode.EXECUTE, resolvedModel);
                    }

                    // Store stage result for downstream input mappings
                    storeStageResult(schema.getId(), runId, stageNodeId, existingNode, scratch);

                    Node executedNode = existingNode != null ? existingNode : scratch;
                    if (executedNode != null
                            && "review".equals(executedNode.getType())
                            && executedNode.getStatus() == Node.NodeStatus.AWAITING_APPROVAL) {
                        executionRepository.updateRunStageStatus(runId, stageNodeId, "paused");
                        log.info("Pipeline paused at stage {} awaiting approval", stageNodeId);
                        executionRepository.updateRunStatus(runId, "paused",
                                "Awaiting review approval for " + stageNodeId);
                        if (webSocketHandler != null) {
                            webSocketHandler.sendLiveUpdate(schema.getId(), "pipeline_paused",
                                    Map.of("stageId", stageNodeId, "status", "paused",
                                            "reason", "awaiting_approval"));
                        }
                        pipelineResumeState.put(schema.getId(), completedStages + 1);
                        paused = true;
                        break;
                    }

                    executionRepository.updateRunStageStatus(runId, stageNodeId, "completed");

                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schema.getId(), "success",
                                "Stage completed: " + stage.getName(), stageNodeId);
                        webSocketHandler.sendLiveUpdate(schema.getId(), "pipeline_stage_completed",
                                Map.of("stageId", stageNodeId, "status", "completed"));
                    }
                } catch (Exception e) {
                    hasFailure = true;
                    executionRepository.updateRunStageStatus(runId, stageNodeId, "failed");
                    log.error("Stage {} failed: {}", stage.getName(), e.getMessage(), e);
                    if (webSocketHandler != null) {
                        webSocketHandler.sendError(schema.getId(), stageNodeId,
                                "Stage failed: " + stage.getName() + " - " + e.getMessage());
                        webSocketHandler.sendLiveUpdate(schema.getId(), "pipeline_stage_failed",
                                Map.of("stageId", stageNodeId, "status", "failed", "error", e.getMessage()));
                    }
                }
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
            for (Node n : schema.getNodes()) nodeMap.put(n.getId(), n);
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
        int totalRetry = retryStages.size();
        int completedRetry = 0;

        for (List<Stage> level : retryLevels) {
            if (cancelFlag.get()) break;
            for (Stage stage : level) {
                if (cancelFlag.get()) break;

                Node existingNode = nodeMap.get(stage.getId());
                try {
                    executionRepository.updateRunStageStatus(runId, stage.getId(), "running");

                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schema.getId(), "info",
                                "Stage started: " + stage.getName(), stage.getId());
                    }

                    String resolvedModel = resolveStageModel(stage, schema);

                    // Resolve input mappings from upstream stages
                    if (existingNode != null && existingNode.getData() != null) {
                        resolveInputMappings(stage, existingNode.getData(), schemaId);
                    }

                    Node scratchNode = null;
                    if (existingNode != null) {
                        nodeExecutor.executeNode(existingNode, schemaId, cancelFlag,
                                ExecutionMode.EXECUTE, resolvedModel);
                    } else {
                        scratchNode = stageToScratchNode(stage, schema.getId());
                        resolveInputMappings(stage, scratchNode.getData(), schemaId);
                        nodeExecutor.executeNode(scratchNode, schemaId, cancelFlag,
                                ExecutionMode.EXECUTE, resolvedModel);
                    }

                    // Store stage result for downstream input mappings
                    storeStageResult(schemaId, runId, stage.getId(), existingNode, scratchNode);

                    executionRepository.updateRunStageStatus(runId, stage.getId(), "completed");

                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schema.getId(), "success",
                                "Stage completed: " + stage.getName(), stage.getId());
                    }
                } catch (Exception e) {
                    executionRepository.updateRunStageStatus(runId, stage.getId(), "failed");
                    log.error("Retry stage {} failed: {}", stage.getName(), e.getMessage(), e);
                    if (webSocketHandler != null) {
                        webSocketHandler.sendError(schema.getId(), stage.getId(),
                                "Retry stage failed: " + stage.getName() + " - " + e.getMessage());
                    }
                    executionRepository.updateRunCompleted(runId, "failed", 0, 0.0);
                    return;
                }
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
        if (resumeIndex == null) {
            log.warn("No pipeline resume state found for schema {}", schemaId);
            return;
        }

        Pipeline pipeline = schema.getPipeline();
        List<Stage> stages = pipeline.getStages();
        if (stages == null || stages.isEmpty() || resumeIndex >= stages.size()) {
            log.warn("No stages to resume for schema {}", schemaId);
            return;
        }

        ExecutionRun run = executionRepository.getLatestRunBySchemaAndStatus(schemaId, "paused");
        if (run == null) {
            log.warn("No paused pipeline run found for schema {}", schemaId);
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

        // Re-run remaining stages — strip deps on already-completed stages
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
        Pipeline remainingPipeline = new Pipeline();
        remainingPipeline.setId(pipeline.getId());
        remainingPipeline.setName(pipeline.getName());
        remainingPipeline.setStages(remainingStages);
        schema.setPipeline(remainingPipeline);
        schemaRepository.save(schema);

        // Execute remaining pipeline
        Map<String, Node> nodeMap = new HashMap<>();
        if (schema.getNodes() != null) {
            for (Node n : schema.getNodes()) nodeMap.put(n.getId(), n);
        }

        List<List<Stage>> remainingLevels = topologicalSortStages(remainingStages);
        int totalRemaining = remainingStages.size();
        int completedRemaining = 0;
        int initialCompleted = resumeIndex;

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        cancelFlags.put(schemaId, cancelFlag);
        stageResults.put(schemaId, new ConcurrentHashMap<>());

        // Pre-populate stageResults with completed stages' outputs
        if (run.getStageOutputs() != null) {
            for (String completedId : completedStageIds) {
                String output = run.getStageOutputs().get(completedId);
                if (output != null) {
                    stageResults.get(schemaId).put(completedId, output);
                }
            }
        }

        for (List<Stage> level : remainingLevels) {
            if (cancelFlag.get()) break;

            for (Stage stage : level) {
                if (cancelFlag.get()) break;

                Node existingNode = nodeMap.get(stage.getId());
                try {
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schema.getId(), "info",
                                "Stage started: " + stage.getName(), stage.getId());
                    }

                    String resolvedModel = resolveStageModel(stage, schema);

                    // Resolve input mappings from upstream stages
                    if (existingNode != null && existingNode.getData() != null) {
                        resolveInputMappings(stage, existingNode.getData(), schemaId);
                    }

                    Node scratchNode = null;
                    if (existingNode != null) {
                        nodeExecutor.executeNode(existingNode, schemaId, cancelFlag,
                                ExecutionMode.EXECUTE, resolvedModel);
                    } else {
                        scratchNode = stageToScratchNode(stage, schema.getId());
                        resolveInputMappings(stage, scratchNode.getData(), schemaId);
                        nodeExecutor.executeNode(scratchNode, schemaId, cancelFlag,
                                ExecutionMode.EXECUTE, resolvedModel);
                    }

                    // Store stage result for downstream input mappings
                    storeStageResult(schemaId, run.getId(), stage.getId(), existingNode, scratchNode);

                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schema.getId(), "success",
                                "Stage completed: " + stage.getName(), stage.getId());
                    }
                } catch (Exception e) {
                    log.error("Stage {} failed: {}", stage.getName(), e.getMessage(), e);
                    if (webSocketHandler != null) {
                        webSocketHandler.sendError(schema.getId(), stage.getId(),
                                "Stage failed: " + stage.getName() + " - " + e.getMessage());
                    }
                }
            }

            completedRemaining += level.size();
        }

        if (webSocketHandler != null) {
            if (cancelFlag.get()) {
                webSocketHandler.sendError(schema.getId(), "system", "Pipeline cancelled");
            } else {
                int totalCompleted = initialCompleted + completedRemaining;
                webSocketHandler.sendLog(schema.getId(), "success",
                        "Pipeline completed: " + totalCompleted + "/" + stages.size() + " stages", null);
                webSocketHandler.sendComplete(schema.getId(), 0, totalCompleted);
            }
        }

        executionRepository.updateRunCompleted(run.getId(), cancelFlag.get() ? "cancelled" : "completed", 0, 0.0);

        // Restore full pipeline
        Pipeline fullPipeline = new Pipeline();
        fullPipeline.setId(pipeline.getId());
        fullPipeline.setName(pipeline.getName());
        fullPipeline.setDescription(pipeline.getDescription());
        fullPipeline.setStages(stages);
        schema.setPipeline(fullPipeline);
        schemaRepository.save(schema);
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
     */
    private void resolveInputMappings(Stage stage, Node.NodeData data, String schemaId) {
        Map<String, String> mapping = stage.getInputMapping();
        if (mapping == null || mapping.isEmpty() || data == null) return;

        Map<String, Object> config = data.getConfig();
        if (config == null) {
            config = new HashMap<>();
            data.setConfig(config);
        }

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
            if (sourceResult == null || sourceResult.isEmpty()) continue;

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
}
