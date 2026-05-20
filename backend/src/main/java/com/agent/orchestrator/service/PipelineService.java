package com.agent.orchestrator.service;

import com.agent.orchestrator.model.*;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schema.getId(), "info",
                                "Stage started: " + stage.getName(), stageNodeId);
                        webSocketHandler.sendLiveUpdate(schema.getId(), "pipeline_stage_started",
                                Map.of("stageId", stageNodeId, "name", stage.getName(), "status", "running"));
                    }

                    String resolvedModel = resolveStageModel(stage, schema);

                    if (existingNode != null) {
                        nodeExecutor.executeNode(existingNode, schema.getId(), cancelFlag,
                                ExecutionMode.EXECUTE, resolvedModel);
                    } else {
                        scratch = stageToScratchNode(stage, schema.getId());
                        nodeExecutor.executeNode(scratch, schema.getId(), cancelFlag,
                                ExecutionMode.EXECUTE, resolvedModel);
                        stageResults.get(schema.getId()).put(stageNodeId,
                                scratch.getData() != null ? scratch.getData().getResult() : "");
                    }

                    Node executedNode = existingNode != null ? existingNode : scratch;
                    if (executedNode != null
                            && "review".equals(executedNode.getType())
                            && executedNode.getStatus() == Node.NodeStatus.AWAITING_APPROVAL) {
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

                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schema.getId(), "success",
                                "Stage completed: " + stage.getName(), stageNodeId);
                        webSocketHandler.sendLiveUpdate(schema.getId(), "pipeline_stage_completed",
                                Map.of("stageId", stageNodeId, "status", "completed"));
                    }
                } catch (Exception e) {
                    hasFailure = true;
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

        // Re-run remaining stages
        List<Stage> remainingStages = stages.subList(resumeIndex, stages.size());
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
                    if (existingNode != null) {
                        nodeExecutor.executeNode(existingNode, schemaId, cancelFlag,
                                ExecutionMode.EXECUTE, resolvedModel);
                    } else {
                        Node scratch = stageToScratchNode(stage, schema.getId());
                        nodeExecutor.executeNode(scratch, schemaId, cancelFlag,
                                ExecutionMode.EXECUTE, resolvedModel);
                    }

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

    private Node stageToScratchNode(Stage stage, String schemaId) {
        Node node = new Node();
        node.setId(stage.getId() != null ? stage.getId() : "stage-" + UUID.randomUUID());
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
