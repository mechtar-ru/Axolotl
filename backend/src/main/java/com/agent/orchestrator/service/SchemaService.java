package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.ExecutionCheckpoint;
import com.agent.orchestrator.model.Stage;
import com.agent.orchestrator.model.ExecutionError;
import com.agent.orchestrator.model.SchemaValidationResult;
import com.agent.orchestrator.model.ExecutionRecord;
import com.agent.orchestrator.model.ExecutionRun;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.NodeExecution;
import com.agent.orchestrator.model.ToolPermission;
import com.agent.orchestrator.model.Task;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.*;

@Service
public class SchemaService {

    private static final Logger log = LoggerFactory.getLogger(SchemaService.class);

    private final Neo4jSchemaRepository schemaRepository;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final MemPalaceClient memPalaceClient;
    private final SettingsService settingsService;
    private final MetricsService metricsService;
    private final NodeExecutor nodeExecutor;
    private final SchemaExporter schemaExporter;
    private final LlmService llmService;
    private final PlanService planService;
    private final ExecutionRepository executionRepository;
    private final PipelineService pipelineService;
    private final ExecutionStateManager stateManager;
    private final SchemaValidator schemaValidator;

    private final Map<String, List<ExecutionRun>> executionRuns = new ConcurrentHashMap<>();
    private final Map<String, ExecutionRun> pausedRuns = new ConcurrentHashMap<>();
    private final List<ExecutionRecord> executionHistory = Collections.synchronizedList(new ArrayList<>());
    private final Object executionHistoryLock = new Object();
    private static final int MAX_HISTORY = 100;

    public SchemaService(Neo4jSchemaRepository schemaRepository,
            ExecutionWebSocketHandler webSocketHandler,
            MemPalaceClient memPalaceClient,
            SettingsService settingsService,
            MetricsService metricsService,
            NodeExecutor nodeExecutor,
            SchemaExporter schemaExporter,
            LlmService llmService,
            PlanService planService,
            ExecutionRepository executionRepository,
            PipelineService pipelineService,
            ExecutionStateManager stateManager,
            SchemaValidator schemaValidator) {
        this.schemaRepository = schemaRepository;
        this.webSocketHandler = webSocketHandler;
        this.memPalaceClient = memPalaceClient;
        this.settingsService = settingsService;
        this.metricsService = metricsService;
        this.nodeExecutor = nodeExecutor;
        this.schemaExporter = schemaExporter;
        this.llmService = llmService;
        this.planService = planService;
        this.executionRepository = executionRepository;
        this.pipelineService = pipelineService;
        this.stateManager = stateManager;
        this.schemaValidator = schemaValidator;
    }

    @jakarta.annotation.PostConstruct
    void init() {
        initDemoSchema();
    }

    // ────────────────────────── CRUD ──────────────────────────

    public List<WorkflowSchema> getAllSchemas() {
        log.info("getAllSchemas() called - fetching from Neo4j");
        List<WorkflowSchema> schemas = schemaRepository.findAll();
        log.info("Returned {} schemas from repository", schemas.size());
        return schemas.stream().map(this::sanitizeSchema).toList();
    }

    public List<WorkflowSchema> getSchemasByUserId(String userId) {
        if (userId != null && !userId.isBlank()) {
            List<WorkflowSchema> userSchemas = schemaRepository.findByUserId(userId);
            if (!userSchemas.isEmpty()) {
                return userSchemas.stream().map(this::sanitizeSchema).toList();
            }
        }
        return schemaRepository.findAll().stream().map(this::sanitizeSchema).toList();
    }

    public WorkflowSchema getSchema(String id) {
        return sanitizeSchema(schemaRepository.findById(id));
    }

    public WorkflowSchema createSchema(WorkflowSchema schema) {
        if (schema == null) {
            throw new IllegalArgumentException("Schema must not be null");
        }
        if (schema.getName() == null || schema.getName().isBlank()) {
            throw new IllegalArgumentException("Schema name is required");
        }
        String id = UUID.randomUUID().toString();
        schema.setId(id);
        schema.setCreatedAt(Instant.now().toString());
        schema.setUpdatedAt(Instant.now().toString());

        // Auto-create target directory on schema creation
        if (schema.getTargetPath() != null && !schema.getTargetPath().isBlank()) {
            try {
                java.nio.file.Files.createDirectories(java.nio.file.Path.of(schema.getTargetPath()));
                log.info("Создана директория targetPath: {}", schema.getTargetPath());
            } catch (java.io.IOException e) {
                log.warn("Не удалось создать директорию targetPath {}: {}", schema.getTargetPath(), e.getMessage(), e);
            }
        }

        schemaRepository.save(schema);
        log.info("Создана схема: {} (ID: {})", schema.getName(), id);
        return schema;
    }

    public WorkflowSchema updateSchema(String id, WorkflowSchema incoming) {
        WorkflowSchema existing = schemaRepository.findById(id);
        if (existing == null) existing = new WorkflowSchema();
        existing.setId(id);
        existing.setUpdatedAt(Instant.now().toString());

        // Partial merge: only overwrite non-null fields from incoming
        if (incoming.getName() != null) existing.setName(incoming.getName());
        if (incoming.getDescription() != null) existing.setDescription(incoming.getDescription());
        if (incoming.getDefaultModel() != null) existing.setDefaultModel(incoming.getDefaultModel());
        if (incoming.getDefaultTools() != null) existing.setDefaultTools(incoming.getDefaultTools());
        if (incoming.getDefaultToolPermissions() != null) existing.setDefaultToolPermissions(incoming.getDefaultToolPermissions());
        if (incoming.getMetadata() != null) existing.setMetadata(incoming.getMetadata());
        if (incoming.getAppType() != null) existing.setAppType(incoming.getAppType());
        if (incoming.getTargetPath() != null) existing.setTargetPath(incoming.getTargetPath());
        if (incoming.getTargetPathConflictAction() != null) existing.setTargetPathConflictAction(incoming.getTargetPathConflictAction());
        if (incoming.getVersion() != null) existing.setVersion(incoming.getVersion());
        if (incoming.getPipeline() != null) existing.setPipeline(incoming.getPipeline());
        if (incoming.getNodes() != null) {
            existing.setNodes(incoming.getNodes().stream().filter(n -> n != null && n.getId() != null).toList());
        }
        if (incoming.getEdges() != null) {
            existing.setEdges(incoming.getEdges().stream().filter(e -> e != null && e.getId() != null).toList());
        }
        if (incoming.getUserId() != null) existing.setUserId(incoming.getUserId());
        if (incoming.getWorkspaceId() != null) existing.setWorkspaceId(incoming.getWorkspaceId());
        if (incoming.getPlanningModels() != null) existing.setPlanningModels(incoming.getPlanningModels());
        if (incoming.getPlanningOutline() != null) existing.setPlanningOutline(incoming.getPlanningOutline());
        if (incoming.getPlanningRefinedPlan() != null) existing.setPlanningRefinedPlan(incoming.getPlanningRefinedPlan());
        if (incoming.getPlanningContext() != null) existing.setPlanningContext(incoming.getPlanningContext());

        schemaRepository.save(existing);

        if (memPalaceClient.isEnabled()) {
            int nodeCount = existing.getNodes() != null ? existing.getNodes().size() : 0;
            int edgeCount = existing.getEdges() != null ? existing.getEdges().size() : 0;
            String nodeTypes = existing.getNodes() != null && !existing.getNodes().isEmpty()
                    ? existing.getNodes().stream()
                        .map(n -> n.getType() + "(" + n.getName() + ")")
                        .reduce((a, b) -> a + ", " + b).orElse("")
                    : "";
            String versionInfo = String.format(
                    "Версия схемы '%s' (обновлено: %s)\nУзлов: %d, Связей: %d\nУзлы: [%s]",
                    existing.getName(), existing.getUpdatedAt(), nodeCount, edgeCount, nodeTypes);
            memPalaceClient.addDrawer("axolotl", "schema-versions",
                    versionInfo, "schema:" + id);
        }

        log.info("Обновлена схема: {} (ID: {})", existing.getName(), id);
        return existing;
    }

    public void deleteSchema(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Schema ID is required");
        }
        cancelExecution(id);
        schemaRepository.delete(id);
        log.info("Удалена схема: {}", id);
    }

    // ────────────────────────── Export / Import ──────────────────────────

    public WorkflowSchema exportSchema(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Schema ID is required");
        }
        return schemaRepository.findById(id);
    }

    public String exportToMermaid(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Schema ID is required");
        }
        return schemaExporter.exportToMermaid(id);
    }

    public String exportToPython(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Schema ID is required");
        }
        return schemaExporter.exportToPython(id);
    }

    public WorkflowSchema importSchema(WorkflowSchema schema, String userId) {
        if (schema == null) {
            throw new IllegalArgumentException("Imported schema must not be null");
        }
        if (schema.getName() == null || schema.getName().isBlank()) {
            throw new IllegalArgumentException("Imported schema name is required");
        }
        String newId = UUID.randomUUID().toString();
        schema.setId(newId);
        schema.setUserId(userId);
        schema.setCreatedAt(Instant.now().toString());
        schema.setUpdatedAt(Instant.now().toString());

        // Strip execution state from nodes
        if (schema.getNodes() != null) {
            for (Node node : schema.getNodes()) {
                node.setStatus(null);
                if (node.getData() != null) {
                    node.getData().setMessages(null);
                    node.getData().setResult(null);
                }
            }
        }

        // Auto-create target directory
        if (schema.getTargetPath() != null && !schema.getTargetPath().isBlank()) {
            try {
                java.nio.file.Files.createDirectories(java.nio.file.Path.of(schema.getTargetPath()));
                log.info("Created targetPath directory: {}", schema.getTargetPath());
            } catch (java.io.IOException e) {
                log.warn("Could not create targetPath {}: {}", schema.getTargetPath(), e.getMessage());
            }
        }

        schemaRepository.save(schema);
        log.info("Imported schema: {} (ID: {})", schema.getName(), newId);
        return schema;
    }

    // ────────────────────────── Execution ──────────────────────────

    public SchemaValidationResult validateSchema(String id) {
        WorkflowSchema schema = schemaRepository.findById(id);
        if (schema == null) {
            SchemaValidationResult result = new SchemaValidationResult();
            result.addError("schema", "Schema not found: " + id);
            return result;
        }
        return schemaValidator.validate(schema);
    }

    public void executeSchema(String id) {
        WorkflowSchema schema = schemaRepository.findById(id);
        if (schema == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Schema not found: " + id);

        // Validate schema before execution
        SchemaValidationResult validation = schemaValidator.validate(schema);
        if (!validation.isValid()) {
            log.warn("Schema validation failed for '{}': {} error(s)", schema.getName(), validation.getErrors().size());
            throw new SchemaValidationException(validation);
        }

        // Fall back to auth context userId for model resolution when schema has none
        if (schema.getUserId() == null) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
                schema.setUserId(auth.getName());
                log.info("Set userId={} on schema {} from security context (auth={}, isAuth={})",
                        auth.getName(), id, auth.getName(), auth.isAuthenticated());
            } else {
                log.warn("Could not get userId: auth={}, isAuth={}, name={}",
                        auth != null, auth != null ? auth.isAuthenticated() : "N/A",
                        auth != null ? auth.getName() : "N/A");
            }
        } else {
            log.info("Schema {} already has userId={}", id, schema.getUserId());
        }

        if (schema.getNodes() != null) {
            for (Node node : schema.getNodes()) {
                node.setStatus(Node.NodeStatus.IDLE);
            }
        }

        if (metricsService != null) {
            metricsService.recordSchemaExecutionStart();
        }

        // Derive stages from canvas nodes and delegate to PipelineService
        List<Stage> stages = PipelineService.createStagesFromNodes(schema);
        if (stages.isEmpty()) {
            throw new RuntimeException("No stages derived from canvas nodes for schema " + id);
        }
        pipelineService.executeDerivedStages(id, schema, stages);
    }

    public void cancelExecution(String id) {
        pipelineService.cancelPipeline(id);
        log.info("Остановка выполнения схемы запрошена: {}", id);
    }

    // ────────────────────────── Execution Runs / Resilience ──────────────────────────

    /**
     * Find all execution runs for a given schema (persisted).
     */
    public List<ExecutionRun> findExecutionRuns(String schemaId) {
        return executionRepository.getRunsBySchema(schemaId);
    }

    /**
     * Get the paused run for a schema, if any (persisted).
     */
    public ExecutionRun getPausedRun(String schemaId) {
        return executionRepository.getLatestRunBySchemaAndStatus(schemaId, "paused");
    }

    /**
     * Resume a paused execution for the given schema.
     * Delegates to PipelineService which handles both PIPELINE and EXECUTE mode runs.
     */
    public void resumeExecution(String schemaId) {
        pipelineService.resumePipeline(schemaId);
    }

    public void resumeExecution(String schemaId, WorkflowSchema schema) {
        resumeExecution(schemaId);
    }

    public void resumeExecution(String schemaId, WorkflowSchema schema, String parentRunId) {
        resumeExecution(schemaId, parentRunId);
    }

    public List<NodeExecution> getExecutionNodes(String runId) {
        return executionRepository.getNodeExecutionsByRun(runId);
    }

    // ────────────────────────── History ──────────────────────────

    public List<ExecutionRecord> getExecutionHistory(String schemaId) {
        // Try Neo4j first, fall back to in-memory cache
        return executionRepository.getExecutionRecordsBySchema(schemaId);
    }

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

    /**
     * Handle review feedback from the UI feedback endpoint.
     * Stores feedback and history in execution state, then triggers re-generation.
     */
    public void handleReviewFeedback(String executionId, String nodeId, String feedback, List<Map<String, Object>> history) {
        String schemaId = resolveSchemaIdFromExecution(executionId);
        if (schemaId == null) {
            log.warn("Could not resolve schemaId from executionId: {}", executionId);
            return;
        }

        // Store feedback in the node result map for the review node to pick up
        String feedbackKey = schemaId + ":" + nodeId + ":feedback";
        nodeExecutor.getNodeResults().computeIfAbsent(schemaId, k -> new ConcurrentHashMap<>())
                .put(feedbackKey, feedback);

        // Store feedback history
        String historyKey = schemaId + ":" + nodeId + ":feedbackHistory";
        nodeExecutor.getNodeResults().computeIfAbsent(schemaId, k -> new ConcurrentHashMap<>())
                .put(historyKey, history != null ? history.toString() : "[]");

        // Find the paused node and resume it from AWAITING_APPROVAL → RUNNING
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

    /**
     * Handle review approval — store approval flag and resume execution
     * so the review node can skip re-review on next run.
     */
    public void handleReviewApprove(String executionId, String nodeId) {
        handleReviewApprove(executionId, nodeId, resolveSchemaIdFromExecution(executionId));
    }

    public void handleReviewApprove(String executionId, String nodeId, String schemaId) {
        if (schemaId == null) {
            log.warn("Could not resolve schemaId from executionId: {}", executionId);
            return;
        }

        // Release any stuck 'resuming' runs so claimPausedRun can find the right one
        executionRepository.releasePausedRun(schemaId);

        // Atomically claim the paused run — prevents TOCTOU on concurrent approve
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

        // Non-pipeline (EXECUTE mode) — handle through PipelineService resume
        log.info("Review node {} approved, resuming execution via PipelineService", nodeId);
        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "info",
                    "Plan approved, resuming execution", nodeId);
            webSocketHandler.sendLiveUpdate(schemaId, "review_approved",
                    Map.of("nodeId", nodeId, "status", "RUNNING"));
        }
        pipelineService.resumePipeline(schemaId, claimed.getId());
    }

    /**
     * Resume execution for a specific paused run by runId.
     * Unlike resumeExecution(schemaId) which claims the latest paused run,
     * this method targets the exact run specified.
     */
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

    /**
     * Resolve schema ID from an executionId that may be either a run ID or a schema ID.
     */
    private String resolveSchemaIdFromExecution(String executionId) {
        // Try as run ID first
        try {
            ExecutionRun run = executionRepository.getRunById(executionId);
            if (run != null && run.getSchemaId() != null) {
                return run.getSchemaId();
            }
        } catch (Exception e) {
            log.debug("executionId {} is not a run ID: {}", executionId, e.getMessage());
        }
        // Fall back — assume it's a schema ID
        return executionId;
    }

    /**
     * Handle review rejection — fail the node.
     */
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

    /**
     * Handle diff review approval — keep changes, clean up backups, resume pipeline.
     */
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

        // Resume the pipeline
        executionRepository.releasePausedRun(schemaId);
        ExecutionRun claimed = executionRepository.claimPausedRun(schemaId);
        if (claimed == null) {
            log.warn("No paused pipeline run found for schema {} after diff approval", schemaId);
            return;
        }
        pipelineService.resumePipeline(schemaId, claimed.getId());
    }

    /**
     * Handle diff review rejection — restore original content from backup.
     */
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

        // Resume pipeline to continue (next stages will see original files)
        executionRepository.releasePausedRun(schemaId);
        ExecutionRun claimed = executionRepository.claimPausedRun(schemaId);
        if (claimed == null) {
            log.warn("No paused pipeline run found for schema {} after diff rejection", schemaId);
            return;
        }
        pipelineService.resumePipeline(schemaId, claimed.getId());
    }

    /**
     * Get all node results for a given execution (used by output summary report).
     */
    public Map<String, String> getExecutionResults(String executionId) {
        Map<String, String> results = nodeExecutor.getNodeResults().get(executionId);
        if (results == null) {
            return new HashMap<>();
        }
        return new HashMap<>(results);
    }

    /**
     * Get the generated files registry for a schema execution.
     */
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

    // ────────────────────────── Private helpers ──────────────────────────

    private void recordExecution(WorkflowSchema schema, long startTime, long totalTimeMs,
                                  int totalNodes, int completedNodes, String status) {
        ExecutionRecord record = new ExecutionRecord();
        record.setId("exec-" + UUID.randomUUID());
        record.setSchemaId(schema.getId());
        record.setSchemaName(schema.getName());
        record.setStartTime(startTime);
        record.setEndTime(startTime + totalTimeMs);
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

        // Persist to Neo4j
        executionRepository.saveExecutionRecord(record);

        synchronized (executionHistoryLock) {
            executionHistory.add(record);
            if (executionHistory.size() > MAX_HISTORY) {
                executionHistory.subList(0, executionHistory.size() - MAX_HISTORY).clear();
            }
        }
    }

    private Set<String> computeSkippedNodes(WorkflowSchema schema, Map<String, String> conditionResults) {
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

    private List<List<Node>> getExecutionLevels(WorkflowSchema schema) {
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
                log.warn("Обнаружены циклические зависимости!");
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

        log.info("Уровни выполнения:");
        for (int i = 0; i < levels.size(); i++) {
            log.info("  Уровень {}: {}", i,
                    levels.get(i).stream().map(Node::getName).reduce((a, b) -> a + ", " + b).orElse(""));
        }

        return levels;
    }

    private void initDemoSchema() {
        if (schemaRepository.findById("demo-1") != null) {
            return;
        }

        WorkflowSchema demo = new WorkflowSchema();
        demo.setId("demo-1");
        demo.setName("Демо: Анализ данных");
        demo.setDescription("Простая схема: источник → агент → вывод");
        demo.setVersion("1.0");

        List<Node> nodes = new ArrayList<>();

        Node source = new Node();
        source.setId("source-1");
        source.setType("source");
        source.setName("Источник");
        Node.Position pos1 = new Node.Position();
        pos1.setX(100);
        pos1.setY(200);
        source.setPosition(pos1);
        nodes.add(source);

        Node agent = new Node();
        agent.setId("agent-1");
        agent.setType("agent");
        agent.setName("AI Аналитик");
        Node.Position pos2 = new Node.Position();
        pos2.setX(400);
        pos2.setY(200);
        agent.setPosition(pos2);
        Node.NodeData data = new Node.NodeData();
        data.setUserPrompt(
                "Ты опытный аналитик. Проанализируй предоставленные данные и сделай выводы. Ответ должен быть на русском языке.");
        data.setModel("local");
        agent.setData(data);
        nodes.add(agent);

        Node output = new Node();
        output.setId("output-1");
        output.setType("output");
        output.setName("Результат");
        Node.Position pos3 = new Node.Position();
        pos3.setX(700);
        pos3.setY(200);
        output.setPosition(pos3);
        nodes.add(output);

        demo.setNodes(nodes);

        List<Edge> edges = new ArrayList<>();

        Edge edge1 = new Edge();
        edge1.setId("edge-1");
        edge1.setSource("source-1");
        edge1.setTarget("agent-1");
        edge1.setType("data");
        edges.add(edge1);

        Edge edge2 = new Edge();
        edge2.setId("edge-2");
        edge2.setSource("agent-1");
        edge2.setTarget("output-1");
        edge2.setType("data");
        edges.add(edge2);

        demo.setEdges(edges);

        schemaRepository.save(demo);
        log.info("Добавлена демо-схема: {}", demo.getName());
    }

    // ────────────────────────── Re-exported constants (used by TemplateController) ──────────────────────────

    public static final String ARCHITECT_ANALYST_PROMPT = NodeExecutor.ARCHITECT_ANALYST_PROMPT;
    public static final String FEATURE_DESIGNER_PROMPT = NodeExecutor.FEATURE_DESIGNER_PROMPT;
    public static final String TASK_BREAKDOWN_PROMPT = NodeExecutor.TASK_BREAKDOWN_PROMPT;
    public static final String PLANNING_WORKFLOW_USER_PROMPT = NodeExecutor.PLANNING_WORKFLOW_USER_PROMPT;

    // ────────────────────────── Config hash ──────────────────────────

    /**
     * Вычислить SHA-256 хеш конфигурации узла для детекции изменений.
     */
    public String computeConfigHash(Node node, WorkflowSchema schema) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper hashMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.LinkedHashMap<String, Object> hashData = new java.util.LinkedHashMap<>();
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
            log.error("Ошибка вычисления config hash: {}", e.getMessage(), e);
            return UUID.randomUUID().toString();
        }
    }

    private void saveCheckpoint(WorkflowSchema schema, String runId, int waveNum) {
        try {
            ExecutionCheckpoint cp = new ExecutionCheckpoint();
            cp.setId(UUID.randomUUID().toString());
            cp.setRunId(runId);
            cp.setCurrentWave(waveNum);
            cp.setCreatedAt(Instant.now().toString());
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
            log.warn("Ошибка сохранения чекпоинта: {}", e.getMessage(), e);
        }
    }

    // ────────────────────────── Test accessors ──────────────────────────

    List<List<Node>> getExecutionLevelsPublic(WorkflowSchema schema) { return getExecutionLevels(schema); }
    Set<String> computeSkippedNodesPublic(WorkflowSchema schema) { return computeSkippedNodes(schema, nodeExecutor.getConditionResults()); }

    private WorkflowSchema sanitizeSchema(WorkflowSchema schema) {
        if (schema == null) return null;
        if (schema.getNodes() != null) {
            schema.setNodes(schema.getNodes().stream().filter(n -> n != null && n.getId() != null).toList());
        }
        if (schema.getEdges() != null) {
            schema.setEdges(schema.getEdges().stream().filter(e -> e != null && e.getId() != null).toList());
        }
        return schema;
    }

    private String resolveModel(String nodeModel, WorkflowSchema schema) {
        if (nodeModel != null && !nodeModel.isBlank()) {
            log.debug("resolveModel: using nodeModel='{}'", nodeModel);
            return nodeModel;
        }
        if (schema.getDefaultModel() != null && !schema.getDefaultModel().isBlank()) {
            log.debug("resolveModel: using schema.defaultModel='{}'", schema.getDefaultModel());
            return schema.getDefaultModel();
        }
        if (schema.getUserId() != null) {
            String userModel = settingsService.getUserDefaultModel(schema.getUserId());
            log.debug("resolveModel: schema.userId='{}', userModel='{}'", schema.getUserId(), userModel);
            if (userModel != null && !userModel.isBlank()) return userModel;
        } else {
            log.debug("resolveModel: schema.userId is NULL");
        }
        String global = settingsService.getGlobalDefaultModel();
        log.debug("resolveModel: global='{}'", global);
        if (global != null && !global.isBlank()) return global;
        log.warn("resolveModel: all fallbacks exhausted, using hardcoded default");
        return "deepseek-v4-flash-free";
    }

    // ────────────────────────── Prompt-to-Schema Generation ──────────────────────────

    // Removed PROMPT_TO_SCHEMA_SYSTEM: LLM-driven schema generation is deprecated.

    public Map<String, Object> generateSchemaFromPrompt(String prompt, String model) {
        Map<String, Object> result = new HashMap<>();
        // Deprecated: Quick Start no longer generates schemas via LLM. Return a clear error
        // instead of executing expensive LLM calls. Kept for backward-compatibility.
        result.put("success", false);
        result.put("error", "generateSchemaFromPrompt has been removed. Use Quick Start fixed pipeline.");
        return result;
    }

}
