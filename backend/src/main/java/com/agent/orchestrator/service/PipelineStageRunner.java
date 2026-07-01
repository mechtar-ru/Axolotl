package com.agent.orchestrator.service;

import com.agent.orchestrator.model.*;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core stage execution logic shared by runPipelineStages, retryPipeline, and resumePipeline.
 * Executes a single pipeline stage: checks run conditions, runs via NodeRouter,
 * handles results (approval pause, diff pause, error), and updates stage status.
 */
@Service
public class PipelineStageRunner {

    private static final Logger log = LoggerFactory.getLogger(PipelineStageRunner.class);

    private final NodeRouter nodeRouter;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final ExecutionStateManager stateManager;
    private final PipelineStatusManager statusManager;
    private final DiffService diffService;
    private final ExecutionRepository executionRepository;
    private final ExecutorService pipelineExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ObjectMapper mapper;

    private static final Duration STAGE_TIMEOUT = Duration.ofMinutes(20);

    enum StageRunResult { COMPLETED, PAUSED, FAILED }

    public PipelineStageRunner(NodeRouter nodeRouter,
                               ExecutionWebSocketHandler webSocketHandler,
                               ExecutionStateManager stateManager,
                               PipelineStatusManager statusManager,
                               DiffService diffService,
                               ExecutionRepository executionRepository,
                               ObjectMapper mapper) {
        this.nodeRouter = nodeRouter;
        this.webSocketHandler = webSocketHandler;
        this.stateManager = stateManager;
        this.statusManager = statusManager;
        this.diffService = diffService;
        this.executionRepository = executionRepository;
        this.mapper = mapper;
    }

    /**
     * Execute a single pipeline stage. Handles:
     * - Setting the stage status to running
     * - Sending WebSocket progress/log events
     * - Executing the node via NodeRouter (with timeout)
     * - Saving the result to stateManager / executionRepository
     * - Checking for review approval pause
     * - Checking for pending diff review pause
     * - Updating stage status on completion or failure
     * - Handling cancellation
     *
     * @return COMPLETED, PAUSED, or FAILED depending on outcome
     */
    public StageRunResult executeStage(Stage stage, WorkflowSchema schema, String runId,
                                        Map<String, Node> nodeMap, AtomicBoolean cancelFlag,
                                        String schemaId, int stageIndex, boolean skipApprovalCheck) {
        AtomicReference<Node> existingNodeRef = new AtomicReference<>(nodeMap.get(stage.getId()));
        AtomicReference<Node> scratchRef = new AtomicReference<>();

        try {
            if (cancelFlag.get()) {
                log.warn("Pipeline cancelled before stage: {}", stage.getName());
                return StageRunResult.FAILED;
            }

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
                        // Supports both "enabledTools" and "tools" keys
                        Object tools = stage.getConfig().get("enabledTools");
                        if (tools == null) {
                            tools = stage.getConfig().get("tools");
                        }
                        if (tools instanceof List) {
                            try {
                                @SuppressWarnings("unchecked")
                                List<String> toolList = (List<String>) tools;
                                nd.setEnabledTools(toolList);
                            } catch (Exception e) {
                                log.warn("Could not set enabledTools from stage config: {}", e.getMessage(), e);
                            }
                        }
                    } else if (nd.getConfig() == null && "source".equals(stage.getNodeType())) {
                        nd.setConfig(new HashMap<>());
                    }
                    resolveInputMappings(stage, nd, schemaId);
                    nodeRouter.executeNode(existingNodeRef.get(), schemaId, cancelFlag,
                            ExecutionMode.EXECUTE, resolvedModel);
                } else {
                    Node s = stageToScratchNode(stage, schemaId);
                    scratchRef.set(s);
                    resolveInputMappings(stage, s.getData(), schemaId);
                    nodeRouter.executeNode(s, schemaId, cancelFlag,
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
            CompletableFuture<Void> stageFuture = CompletableFuture.runAsync(executionTask, pipelineExecutor);
            try {
                stageFuture.orTimeout(stageTimeoutMs, TimeUnit.MILLISECONDS).join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof TimeoutException) {
                    stageFuture.cancel(true);
                    cancelFlag.set(true);
                    throw new RuntimeException("Stage timed out after " + (stageTimeoutMs / 1000) + "s: "
                            + stage.getName(), e.getCause());
                }
                stageFuture.cancel(true);
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

            // Pending diff review check
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

    void storeStageResult(String schemaId, String runId, String stageNodeId,
                           Node existingNode, Node scratchNode) {
        Node executed = existingNode != null ? existingNode : scratchNode;
        if (executed == null) return;

        String result = executed.getData() != null && executed.getData().getResult() != null
                ? executed.getData().getResult() : "";

        statusManager.putStageResult(schemaId, stageNodeId, result);

        if (result != null && !result.isEmpty()) {
            executionRepository.updateRunStageOutput(runId, stageNodeId, result);
        }
    }

    String resolveStageModel(Stage stage, WorkflowSchema schema) {
        if (stage.getModel() != null && !stage.getModel().isBlank()) return stage.getModel();
        return schema.getDefaultModel();
    }

    void resolveInputMappings(Stage stage, Node.NodeData data, String schemaId) {
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

    Node stageToScratchNode(Stage stage, String schemaId) {
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

    void persistResumeState(String schemaId, String runId, int resumeIndex) {
        statusManager.storeResumeState(schemaId, resumeIndex);
        executionRepository.updateRunResumeIndexOnly(runId, resumeIndex);
    }

    @PreDestroy
    public void shutdown() {
        pipelineExecutor.shutdownNow();
    }
}
