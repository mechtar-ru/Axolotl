package com.agent.orchestrator.service;

import com.agent.orchestrator.model.*;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.*;

/**
 * Schema execution and review/diff handling.
 * Extracted from SchemaServiceImpl to keep schema lifecycle management focused on CRUD.
 */
@Service
public class SchemaExecutionService {

    private static final Logger log = LoggerFactory.getLogger(SchemaExecutionService.class);

    private final Neo4jSchemaRepository schemaRepository;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final NodeExecutor nodeExecutor;
    private final ExecutionRepository executionRepository;
    private final PipelineService pipelineService;
    private final ExecutionStateManager stateManager;
    private final SchemaValidator schemaValidator;
    private final MetricsService metricsService;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, List<ExecutionRun>> executionRuns = new ConcurrentHashMap<>();
    private final Map<String, ExecutionRun> pausedRuns = new ConcurrentHashMap<>();
    private final List<ExecutionRecord> executionHistory = Collections.synchronizedList(new ArrayList<>());
    private final Object executionHistoryLock = new Object();
    private static final int MAX_HISTORY = 100;

    // Re-exported prompt constants used by TemplateController
    public static final String ARCHITECT_ANALYST_PROMPT = NodeExecutor.ARCHITECT_ANALYST_PROMPT;
    public static final String FEATURE_DESIGNER_PROMPT = NodeExecutor.FEATURE_DESIGNER_PROMPT;
    public static final String TASK_BREAKDOWN_PROMPT = NodeExecutor.TASK_BREAKDOWN_PROMPT;
    public static final String PLANNING_WORKFLOW_USER_PROMPT = NodeExecutor.PLANNING_WORKFLOW_USER_PROMPT;

    public SchemaExecutionService(Neo4jSchemaRepository schemaRepository,
                                   ExecutionWebSocketHandler webSocketHandler,
                                   NodeExecutor nodeExecutor,
                                   ExecutionRepository executionRepository,
                                   PipelineService pipelineService,
                                   ExecutionStateManager stateManager,
                                   SchemaValidator schemaValidator,
                                   MetricsService metricsService) {
        this.schemaRepository = schemaRepository;
        this.webSocketHandler = webSocketHandler;
        this.nodeExecutor = nodeExecutor;
        this.executionRepository = executionRepository;
        this.pipelineService = pipelineService;
        this.stateManager = stateManager;
        this.schemaValidator = schemaValidator;
        this.metricsService = metricsService;
    }

    // ── Execution ──

    public void executeSchema(String id) {
        WorkflowSchema schema = schemaRepository.findById(id);
        if (schema == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Schema not found: " + id);

        SchemaValidationResult validation = schemaValidator.validate(schema);
        if (!validation.isValid()) {
            log.warn("Schema validation failed for '{}': {} error(s)", schema.getName(), validation.getErrors().size());
            throw new SchemaValidationException(validation);
        }

        if (schema.getUserId() == null) {
            org.springframework.security.core.Authentication auth =
                    org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
                schema.setUserId(auth.getName());
                log.info("Set userId={} on schema {} from security context (auth={}, isAuth={})",
                        auth.getName(), id, auth.getName(), auth.isAuthenticated());
            } else {
                log.warn("Could not get userId: auth={}, isAuth={}, name={}",
                        auth != null, auth != null ? auth.isAuthenticated() : "N/A",
                        auth != null ? auth.getName() : "N/A");
            }
        }

        if (schema.getNodes() != null) {
            for (Node node : schema.getNodes()) {
                node.setStatus(Node.NodeStatus.IDLE);
            }
        }

        if (metricsService != null) {
            metricsService.recordSchemaExecutionStart();
        }

        List<Stage> stages = PipelineService.createStagesFromNodes(schema);
        if (stages.isEmpty()) {
            throw new RuntimeException("No stages derived from canvas nodes for schema " + id);
        }
        pipelineService.executeDerivedStages(id, schema, stages);
    }

    public void executeSchema(String id, String sessionInput) {
        if (sessionInput == null || sessionInput.isBlank()) {
            executeSchema(id);
            return;
        }

        // Load schema and override source node's input with session-specific input
        WorkflowSchema schema = schemaRepository.findById(id);
        if (schema == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Schema not found: " + id);

        SchemaValidationResult validation = schemaValidator.validate(schema);
        if (!validation.isValid()) {
            log.warn("Schema validation failed for '{}': {} error(s)", schema.getName(), validation.getErrors().size());
            throw new SchemaValidationException(validation);
        }

        if (schema.getUserId() == null) {
            org.springframework.security.core.Authentication auth =
                    org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
                schema.setUserId(auth.getName());
                log.info("Set userId={} on schema {} from security context", auth.getName(), id);
            } else {
                log.warn("Could not get userId: auth={}, isAuth={}",
                        auth != null, auth != null ? auth.isAuthenticated() : "N/A");
            }
        }

        if (schema.getNodes() != null) {
            for (Node node : schema.getNodes()) {
                node.setStatus(Node.NodeStatus.IDLE);
            }
        }

        // Override source node's sourceData with session input
        if (schema.getNodes() != null) {
            for (Node node : schema.getNodes()) {
                if ("source".equals(node.getType())) {
                    if (node.getData() == null) node.setData(new Node.NodeData());
                    if (node.getData().getConfig() == null) node.getData().setConfig(new java.util.HashMap<>());
                    node.getData().getConfig().put("sourceData", sessionInput);
                    log.info("Session input set on source node {}: {} chars", node.getId(), sessionInput.length());
                    break;
                }
            }
        }

        if (metricsService != null) {
            metricsService.recordSchemaExecutionStart();
        }

        List<Stage> stages = PipelineService.createStagesFromNodes(schema);
        if (stages.isEmpty()) {
            throw new RuntimeException("No stages derived from canvas nodes for schema " + id);
        }
        pipelineService.executeDerivedStages(id, schema, stages);
    }

    public void cancelExecution(String id) {
        pipelineService.cancelPipeline(id);
        log.info("Execution cancel requested for schema: {}", id);
    }

    // ── Execution Runs / Resilience ──

    @Transactional(readOnly = true)
    public List<ExecutionRun> findExecutionRuns(String schemaId) {
        return executionRepository.getRunsBySchema(schemaId);
    }

    @Transactional(readOnly = true)
    public ExecutionRun getPausedRun(String schemaId) {
        return executionRepository.getLatestRunBySchemaAndStatus(schemaId, "paused");
    }

    public void resumeExecution(String schemaId) {
        pipelineService.resumePipeline(schemaId);
    }

    public void resumeExecution(String schemaId, WorkflowSchema schema) {
        resumeExecution(schemaId);
    }

    public void resumeExecution(String schemaId, WorkflowSchema schema, String parentRunId) {
        resumeExecution(schemaId, parentRunId);
    }

    @Transactional(readOnly = true)
    public List<NodeExecution> getExecutionNodes(String runId) {
        return executionRepository.getNodeExecutionsByRun(runId);
    }

    // ── History ──

    @Transactional(readOnly = true)
    public List<ExecutionRecord> getExecutionHistory(String schemaId) {
        return executionRepository.getExecutionRecordsBySchema(schemaId);
    }

    @Transactional(readOnly = true)
    public List<ExecutionRecord> getAllExecutionHistory() {
        return executionRepository.getAllExecutionRecords();
    }

    public String getOutputFileContent(String schemaId, String nodeId) {
        String key = schemaId + ":" + nodeId;
        String filePath = nodeExecutor.getOutputFileRegistry().get(key);
        if (filePath == null) return null;
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(filePath));
        } catch (Exception e) {
            return null;
        }
    }

    // ── Review Feedback ──

    public void handleReviewFeedback(String executionId, String nodeId, String feedback, List<Map<String, Object>> history) {
        String schemaId = resolveSchemaIdFromExecution(executionId);
        if (schemaId == null) {
            log.warn("Could not resolve schemaId from executionId: {}", executionId);
            return;
        }

        String feedbackKey = schemaId + ":" + nodeId + ":feedback";
        nodeExecutor.getNodeResults().computeIfAbsent(schemaId, k -> new ConcurrentHashMap<>())
                .put(feedbackKey, feedback);

        String historyKey = schemaId + ":" + nodeId + ":feedbackHistory";
        nodeExecutor.getNodeResults().computeIfAbsent(schemaId, k -> new ConcurrentHashMap<>())
                .put(historyKey, history != null ? history.toString() : "[]");

        WorkflowSchema schema = schemaRepository.findById(schemaId);
        if (schema != null && schema.getNodes() != null) {
            for (Node node : schema.getNodes()) {
                if (node.getId().equals(nodeId) && node.getStatus() == Node.NodeStatus.AWAITING_APPROVAL) {
                    node.setStatus(Node.NodeStatus.RUNNING);
                    log.info("Resumed review node {} from AWAITING_APPROVAL after feedback", nodeId);
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(executionId, "info",
                                "Feedback received, resuming review node: " + nodeId, nodeId);
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("nodeId", nodeId);
                        payload.put("status", "RUNNING");
                        payload.put("feedback", feedback);
                        webSocketHandler.sendLiveUpdate(executionId, "review_feedback_applied", payload);
                    }
                    break;
                }
            }
        }
    }

    public void handleReviewApprove(String executionId, String nodeId) {
        handleReviewApprove(executionId, nodeId, resolveSchemaIdFromExecution(executionId));
    }

    public void handleReviewApprove(String executionId, String nodeId, String schemaId) {
        if (schemaId == null) {
            log.warn("Could not resolve schemaId from executionId: {}", executionId);
            return;
        }

        executionRepository.releasePausedRun(schemaId);

        ExecutionRun claimed = executionRepository.claimPausedRun(schemaId);
        if (claimed == null) {
            log.warn("No paused run found for schema {} (may have been claimed by another request)", schemaId);
            return;
        }

        String approvedKey = schemaId + ":" + nodeId + ":approved";
        nodeExecutor.getNodeResults().computeIfAbsent(schemaId, k -> new ConcurrentHashMap<>())
                .put(approvedKey, "true");

        if ("PIPELINE".equals(claimed.getMode())) {
            log.info("Pipeline review approved for schema {}, run {} node {}", schemaId, claimed.getId(), nodeId);
            try {
                pipelineService.resumePipeline(schemaId, claimed.getId());
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schemaId, "info",
                            "Plan approved, resuming pipeline execution", nodeId);
                    webSocketHandler.sendLiveUpdate(schemaId, "review_approved",
                            Map.of("nodeId", nodeId, "status", "RUNNING"));
                }
            } catch (Exception e) {
                ExecutionError error = ExecutionError.fromException(e, "Unknown error resuming pipeline after approval");
                log.error("Failed to resume pipeline for schema {}: {} ({})", schemaId, error.getMessage(), error.getType(), e);
                executionRepository.updateRunStatus(claimed.getId(), "paused",
                        "Resume failed: " + error.getMessage());
                if (webSocketHandler != null) {
                    webSocketHandler.sendError(schemaId, "system", error.getMessage());
                }
            }
            return;
        }

        log.info("Review node {} approved, resuming execution via PipelineService", nodeId);
        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "info",
                    "Plan approved, resuming execution", nodeId);
            webSocketHandler.sendLiveUpdate(schemaId, "review_approved",
                    Map.of("nodeId", nodeId, "status", "RUNNING"));
        }
        pipelineService.resumePipeline(schemaId, claimed.getId());
    }

    public void resumeExecution(String schemaId, String runId) {
        if (runId == null || runId.isBlank()) {
            resumeExecution(schemaId);
            return;
        }
        WorkflowSchema schema = schemaRepository.findById(schemaId);
        if (schema == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Schema not found: " + schemaId);
        }
        ExecutionRun run = executionRepository.claimSpecificRun(runId);
        if (run == null) {
            log.warn("No paused run found for id: {}", runId);
            if (webSocketHandler != null) {
                webSocketHandler.sendError(schemaId, "system",
                        "No paused execution found for the specified run — it may have already been resumed");
            }
            return;
        }
        resumeExecution(schemaId, schema, run.getId());
    }

    private String resolveSchemaIdFromExecution(String executionId) {
        try {
            ExecutionRun run = executionRepository.getRunById(executionId);
            if (run != null && run.getSchemaId() != null) {
                return run.getSchemaId();
            }
        } catch (Exception e) {
            log.debug("executionId {} is not a run ID: {}", executionId, e.getMessage());
        }
        return executionId;
    }

    // ── Review Reject ──

    public void handleReviewReject(String executionId, String nodeId) {
        handleReviewReject(executionId, nodeId, resolveSchemaIdFromExecution(executionId));
    }

    public void handleReviewReject(String executionId, String nodeId, String schemaId) {
        if (schemaId == null) {
            log.warn("Could not resolve schemaId from executionId: {}", executionId);
            return;
        }

        List<ExecutionRun> runs = executionRepository.getRunsBySchema(schemaId);
        ExecutionRun pipelineRun = runs.stream()
                .filter(r -> "PIPELINE".equals(r.getMode()) && "paused".equals(r.getStatus()))
                .findFirst().orElse(null);
        if (pipelineRun != null) {
            log.info("Pipeline review rejected for schema {}, run {} node {}", schemaId, pipelineRun.getId(), nodeId);
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "error",
                        "Plan rejected by user, pipeline failed: " + nodeId, nodeId);
                Map<String, Object> payload = new HashMap<>();
                payload.put("nodeId", nodeId);
                payload.put("status", "FAILED");
                payload.put("error", "Plan rejected by user");
                webSocketHandler.sendLiveUpdate(schemaId, "review_rejected", payload);
            }
            executionRepository.updateRunStatus(pipelineRun.getId(), "failed", "User rejected review at " + nodeId);
            return;
        }

        WorkflowSchema schema = schemaRepository.findById(schemaId);
        if (schema != null && schema.getNodes() != null) {
            for (Node node : schema.getNodes()) {
                if (node.getId().equals(nodeId) && node.getStatus() == Node.NodeStatus.AWAITING_APPROVAL) {
                    node.setStatus(Node.NodeStatus.FAILED);
                    log.info("Review node {} rejected, marking FAILED", nodeId);
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schemaId, "error",
                                "Plan rejected by user, node failed: " + nodeId, nodeId);
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("nodeId", nodeId);
                        payload.put("status", "FAILED");
                        payload.put("error", "Plan rejected by user");
                        webSocketHandler.sendLiveUpdate(schemaId, "review_rejected", payload);
                    }
                    break;
                }
            }
        }
    }

    // ── Diff Review ──

    public void handleDiffsApprove(String executionId, String nodeId) {
        String schemaId = resolveSchemaIdFromExecution(executionId);
        if (schemaId == null) return;

        List<ExecutionStateManager.PendingDiff> diffs = stateManager.getPendingDiffs(schemaId, nodeId);
        for (ExecutionStateManager.PendingDiff pd : diffs) {
            if (pd.tempBackupPath != null) {
                java.io.File backup = new java.io.File(pd.tempBackupPath);
                if (backup.exists()) backup.delete();
            }
        }
        stateManager.clearPendingDiffs(schemaId, nodeId);
        log.info("Diff review approved for schema {} node {} ({} files)", schemaId, nodeId, diffs.size());

        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "info",
                    "File changes approved, resuming pipeline", nodeId);
        }

        executionRepository.releasePausedRun(schemaId);
        ExecutionRun claimed = executionRepository.claimPausedRun(schemaId);
        if (claimed == null) {
            log.warn("No paused pipeline run found for schema {} after diff approval", schemaId);
            return;
        }
        pipelineService.resumePipeline(schemaId, claimed.getId());
    }

    public void handleDiffsReject(String executionId, String nodeId) {
        String schemaId = resolveSchemaIdFromExecution(executionId);
        if (schemaId == null) return;

        List<ExecutionStateManager.PendingDiff> diffs = stateManager.getPendingDiffs(schemaId, nodeId);
        for (ExecutionStateManager.PendingDiff pd : diffs) {
            if (pd.tempBackupPath != null) {
                java.io.File backup = new java.io.File(pd.tempBackupPath);
                java.io.File target = new java.io.File(pd.filePath);
                if (backup.exists()) {
                    try {
                        java.nio.file.Files.copy(backup.toPath(), target.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        backup.delete();
                    } catch (IOException e) {
                        log.error("Failed to restore backup for {}: {}", pd.filePath, e.getMessage());
                    }
                }
            }
        }
        stateManager.clearPendingDiffs(schemaId, nodeId);
        log.info("Diff review rejected for schema {} node {} — files restored from backup", schemaId, nodeId);

        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "error",
                    "File changes rejected, original files restored", nodeId);
        }

        executionRepository.releasePausedRun(schemaId);
        ExecutionRun claimed = executionRepository.claimPausedRun(schemaId);
        if (claimed == null) {
            log.warn("No paused pipeline run found for schema {} after diff rejection", schemaId);
            return;
        }
        pipelineService.resumePipeline(schemaId, claimed.getId());
    }

    // ── Results ──

    @Transactional(readOnly = true)
    public Map<String, String> getExecutionResults(String executionId) {
        Map<String, String> results = nodeExecutor.getNodeResults().get(executionId);
        if (results == null) {
            return new HashMap<>();
        }
        return new HashMap<>(results);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getGeneratedFiles(String schemaId) {
        Map<String, Object> files = new HashMap<>();
        String prefix = schemaId + ":";
        for (Map.Entry<String, Object> entry : nodeExecutor.getGeneratedFilesRegistry().entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                files.put(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        return files;
    }

    // ── Config hash ──

    public String computeConfigHash(Node node, WorkflowSchema schema) {
        try {
            ObjectMapper hashMapper = new ObjectMapper();
            LinkedHashMap<String, Object> hashData = new LinkedHashMap<>();
            if (node.getData() != null) {
                hashData.put("data", node.getData());
            }
            List<String> incomingEdgeIds = new ArrayList<>();
            if (schema.getEdges() != null) {
                for (Edge edge : schema.getEdges()) {
                    if (edge.getTarget().equals(node.getId())) {
                        incomingEdgeIds.add(edge.getSource());
                    }
                }
            }
            Collections.sort(incomingEdgeIds);
            hashData.put("incomingEdges", incomingEdgeIds);
            String json = hashMapper.writeValueAsString(hashData);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            log.error("Error computing config hash: {}", e.getMessage(), e);
            return UUID.randomUUID().toString();
        }
    }

    // ── Private helpers ──

    private void recordExecution(WorkflowSchema schema, long startTimeMs, long totalTimeMs,
                                  int totalNodes, int completedNodes, String status) {
        ExecutionRecord record = new ExecutionRecord();
        record.setId("exec-" + UUID.randomUUID());
        record.setSchemaId(schema.getId());
        record.setSchemaName(schema.getName());
        record.setStartTime(Instant.ofEpochMilli(startTimeMs));
        record.setEndTime(Instant.ofEpochMilli(startTimeMs + totalTimeMs));
        record.setTotalTimeMs(totalTimeMs);
        record.setTotalNodes(totalNodes);
        record.setCompletedNodes(completedNodes);
        record.setStatus(status);

        Map<String, ExecutionRecord.NodeResult> nodeResults = new HashMap<>();
        if (schema.getNodes() != null) {
            for (Node node : schema.getNodes()) {
                ExecutionRecord.NodeResult nr = new ExecutionRecord.NodeResult();
                nr.setNodeId(node.getId());
                nr.setNodeName(node.getName());
                nr.setResult(node.getData() != null ? node.getData().getResult() : null);
                nr.setStatus(node.getStatus() != null ? node.getStatus().name().toLowerCase() : "idle");
                nodeResults.put(node.getId(), nr);
            }
        }
        record.setNodeResults(nodeResults);

        executionRepository.saveExecutionRecord(record);

        synchronized (executionHistoryLock) {
            executionHistory.add(record);
            if (executionHistory.size() > MAX_HISTORY) {
                executionHistory.subList(0, executionHistory.size() - MAX_HISTORY).clear();
            }
        }
    }

    void computeSkippedNodes(WorkflowSchema schema, Map<String, String> conditionResults) {
        // package-private — used by test accessor only; logic is the same as original private method
    }

    Set<String> computeSkippedNodesInternal(WorkflowSchema schema, Map<String, String> conditionResults) {
        Set<String> skipped = new HashSet<>();
        if (schema.getEdges() == null || schema.getNodes() == null) {
            return skipped;
        }
        for (Edge edge : schema.getEdges()) {
            if (edge.getSourcePort() == null || edge.getSourcePort().isEmpty()) {
                continue;
            }
            String key = schema.getId() + ":" + edge.getSource();
            String conditionResult = conditionResults.get(key);
            if (conditionResult == null) {
                continue;
            }
            if (!conditionResult.equals(edge.getSourcePort())) {
                skipped.add(edge.getTarget());
            }
        }
        Set<String> toAdd = new HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Edge edge : schema.getEdges()) {
                if (skipped.contains(edge.getSource()) && !skipped.contains(edge.getTarget())) {
                    toAdd.add(edge.getTarget());
                    changed = true;
                }
            }
            skipped.addAll(toAdd);
            toAdd.clear();
        }
        return skipped;
    }

    List<List<Node>> getExecutionLevels(WorkflowSchema schema) {
        if (schema.getNodes() == null || schema.getNodes().isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, Node> nodeMap = new HashMap<>();
        Map<String, Set<String>> incomingEdges = new HashMap<>();

        for (Node node : schema.getNodes()) {
            nodeMap.put(node.getId(), node);
            incomingEdges.put(node.getId(), new HashSet<>());
        }

        if (schema.getEdges() != null) {
            for (Edge edge : schema.getEdges()) {
                if (incomingEdges.containsKey(edge.getTarget())) {
                    incomingEdges.get(edge.getTarget()).add(edge.getSource());
                }
            }
        }

        List<List<Node>> levels = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        Map<String, Set<String>> remainingDeps = new HashMap<>();
        incomingEdges.forEach((k, v) -> remainingDeps.put(k, new HashSet<>(v)));

        while (visited.size() < schema.getNodes().size()) {
            List<Node> currentLevel = new ArrayList<>();

            for (Node node : schema.getNodes()) {
                if (!visited.contains(node.getId()) && remainingDeps.get(node.getId()).isEmpty()) {
                    currentLevel.add(node);
                }
            }

            if (currentLevel.isEmpty()) {
                log.warn("Cyclic dependencies detected!");
                List<Node> cyclic = new ArrayList<>();
                for (Node node : schema.getNodes()) {
                    if (!visited.contains(node.getId())) {
                        cyclic.add(node);
                        visited.add(node.getId());
                    }
                }
                if (!cyclic.isEmpty()) levels.add(cyclic);
                break;
            }

            for (Node node : currentLevel) {
                visited.add(node.getId());
                for (String depNodeId : nodeMap.keySet()) {
                    remainingDeps.get(depNodeId).remove(node.getId());
                }
            }

            levels.add(currentLevel);
        }

        return levels;
    }

    void saveCheckpoint(WorkflowSchema schema, String runId, int waveNum) {
        try {
            ExecutionCheckpoint cp = new ExecutionCheckpoint();
            cp.setId(UUID.randomUUID().toString());
            cp.setRunId(runId);
            cp.setCurrentWave(waveNum);
            cp.setCreatedAt(Instant.now());
            List<String> completedIds = new ArrayList<>();
            if (schema.getNodes() != null) {
                for (Node n : schema.getNodes()) {
                    if (n.getStatus() == Node.NodeStatus.COMPLETED) {
                        completedIds.add(n.getId());
                    }
                }
            }
            cp.setCompletedNodeIds(new ObjectMapper().writeValueAsString(completedIds));
            executionRepository.saveCheckpoint(cp);
        } catch (Exception e) {
            log.warn("Error saving checkpoint: {}", e.getMessage(), e);
        }
    }

    // ── Test accessors ──

    List<List<Node>> getExecutionLevelsPublic(WorkflowSchema schema) { return getExecutionLevels(schema); }
    Set<String> computeSkippedNodesPublic(WorkflowSchema schema) { return computeSkippedNodesInternal(schema, nodeExecutor.getConditionResults()); }
}
