package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.ExecutionCheckpoint;
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

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

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

    private final Map<String, List<ExecutionRun>> executionRuns = new ConcurrentHashMap<>();
    private final Map<String, ExecutionRun> pausedRuns = new ConcurrentHashMap<>();

    private final Map<String, CompletableFuture<?>> runningExecutions = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final List<ExecutionRecord> executionHistory = Collections.synchronizedList(new ArrayList<>());
    private final Object executionHistoryLock = new Object();
    private static final int MAX_HISTORY = 100;
    private static final int MAX_NODE_RESTARTS = 3;
    private final ExecutorService executionExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors());
    private final Map<String, Map<String, Integer>> nodeFailureCounts = new ConcurrentHashMap<>();

    public SchemaService(Neo4jSchemaRepository schemaRepository,
            ExecutionWebSocketHandler webSocketHandler,
            MemPalaceClient memPalaceClient,
            SettingsService settingsService,
            MetricsService metricsService,
            NodeExecutor nodeExecutor,
            SchemaExporter schemaExporter,
            LlmService llmService,
            PlanService planService) {
        this(schemaRepository, webSocketHandler, memPalaceClient, settingsService,
                metricsService, nodeExecutor, schemaExporter, llmService, planService, null);
    }

    public SchemaService(Neo4jSchemaRepository schemaRepository,
            ExecutionWebSocketHandler webSocketHandler,
            MemPalaceClient memPalaceClient,
            SettingsService settingsService,
            MetricsService metricsService,
            NodeExecutor nodeExecutor,
            SchemaExporter schemaExporter,
            LlmService llmService,
            PlanService planService,
            ExecutionRepository executionRepository) {
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
        return schemas;
    }

    public List<WorkflowSchema> getSchemasByUserId(String userId) {
        if (userId != null && !userId.isBlank()) {
            List<WorkflowSchema> userSchemas = schemaRepository.findByUserId(userId);
            if (!userSchemas.isEmpty()) {
                return userSchemas;
            }
        }
        return schemaRepository.findAll();
    }

    public WorkflowSchema getSchema(String id) {
        return sanitizeSchema(schemaRepository.findById(id));
    }

    public WorkflowSchema createSchema(WorkflowSchema schema) {
        String id = UUID.randomUUID().toString();
        schema.setId(id);
        schema.setCreatedAt(Instant.now().toString());
        schema.setUpdatedAt(Instant.now().toString());
        schemaRepository.save(schema);
        log.info("Создана схема: {} (ID: {})", schema.getName(), id);
        return schema;
    }

    public WorkflowSchema updateSchema(String id, WorkflowSchema schema) {
        schema.setId(id);
        schema.setUpdatedAt(Instant.now().toString());
        if (schema.getNodes() != null) {
            schema.setNodes(schema.getNodes().stream().filter(n -> n != null && n.getId() != null).toList());
        }
        if (schema.getEdges() != null) {
            schema.setEdges(schema.getEdges().stream().filter(e -> e != null && e.getId() != null).toList());
        }
        schemaRepository.save(schema);

        if (memPalaceClient.isEnabled()) {
            int nodeCount = schema.getNodes() != null ? schema.getNodes().size() : 0;
            int edgeCount = schema.getEdges() != null ? schema.getEdges().size() : 0;
            String nodeTypes = schema.getNodes() != null && !schema.getNodes().isEmpty()
                    ? schema.getNodes().stream()
                        .map(n -> n.getType() + "(" + n.getName() + ")")
                        .reduce((a, b) -> a + ", " + b).orElse("")
                    : "";
            String versionInfo = String.format(
                    "Версия схемы '%s' (обновлено: %s)\nУзлов: %d, Связей: %d\nУзлы: [%s]",
                    schema.getName(), schema.getUpdatedAt(), nodeCount, edgeCount, nodeTypes);
            memPalaceClient.addDrawer("axolotl", "schema-versions",
                    versionInfo, "schema:" + id);
        }

        log.info("Обновлена схема: {} (ID: {})", schema.getName(), id);
        return schema;
    }

    public void deleteSchema(String id) {
        cancelExecution(id);
        schemaRepository.delete(id);
        log.info("Удалена схема: {}", id);
    }

    // ────────────────────────── Export (delegated) ──────────────────────────

    public String exportToMermaid(String id) {
        return schemaExporter.exportToMermaid(id);
    }

    public String exportToPython(String id) {
        return schemaExporter.exportToPython(id);
    }

    // ────────────────────────── Execution ──────────────────────────

    public void executeSchema(String id) {
        WorkflowSchema schema = schemaRepository.findById(id);
        if (schema == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Schema not found: " + id);
        if (runningExecutions.containsKey(id)) {
            log.warn("Схема уже выполняется: {}", id);
            return;
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

        // Create ExecutionRun for persistence
        String runId = UUID.randomUUID().toString();
        ExecutionRun run = new ExecutionRun();
        run.setId(runId);
        run.setSchemaId(id);
        run.setStatus("running");
        run.setMode("EXECUTE");
        run.setStartedAt(Instant.now().toString());
        run.setUpdatedAt(Instant.now().toString());
        if (executionRepository != null) {
            executionRepository.createRun(run);
            // Create NodeExecution for each node
            if (schema.getNodes() != null) {
                for (Node node : schema.getNodes()) {
                    NodeExecution ne = new NodeExecution();
                    ne.setId(UUID.randomUUID().toString());
                    ne.setRunId(runId);
                    ne.setNodeId(node.getId());
                    ne.setNodeName(node.getName());
                    ne.setNodeType(node.getType());
                    ne.setStatus("pending");
                    ne.setConfigHash(computeConfigHash(node, schema));
                    ne.setStartedAt(Instant.now().toString());
                    executionRepository.createNodeExecution(ne);
                }
            }
        }

        if (metricsService != null) {
            metricsService.recordSchemaExecutionStart();
        }

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        cancelFlags.put(id, cancelFlag);
        Timer.Sample timerSample = metricsService != null ? metricsService.startTimer() : null;
        nodeExecutor.setCurrentRunId(id, runId);
        CompletableFuture<?> future = CompletableFuture.runAsync(
                () -> executeWorkflow(schema, cancelFlag, runId), executionExecutor);
        runningExecutions.put(id, future);
        future.whenComplete((result, ex) -> {
            if (metricsService != null && timerSample != null) {
                metricsService.stopTimer(timerSample);
            }
            runningExecutions.remove(id);
            cancelFlags.remove(id);
            if (ex != null && !(ex instanceof CancellationException)) {
                log.error("Ошибка выполнения схемы {}: {}", id, ex.getMessage());
            }
        });
    }

    public void cancelExecution(String id) {
        AtomicBoolean cancelFlag = cancelFlags.get(id);
        if (cancelFlag != null) {
            cancelFlag.set(true);
        }
        CompletableFuture<?> future = runningExecutions.get(id);
        if (future != null) {
            future.cancel(true);
        }
        log.info("Остановка выполнения схемы запрошена: {}", id);
    }

    // ────────────────────────── Execution Runs / Resilience ──────────────────────────

    /**
     * Find all execution runs for a given schema (persisted).
     */
    public List<ExecutionRun> findExecutionRuns(String schemaId) {
        if (executionRepository == null) return List.of();
        return executionRepository.getRunsBySchema(schemaId);
    }

    /**
     * Get the paused run for a schema, if any (persisted).
     */
    public ExecutionRun getPausedRun(String schemaId) {
        if (executionRepository == null) return null;
        return executionRepository.getLatestRunBySchemaAndStatus(schemaId, "paused");
    }

    /**
     * Resume a paused execution for the given schema.
     */
    public void resumeExecution(String schemaId) {
        WorkflowSchema schema = schemaRepository.findById(schemaId);
        resumeExecution(schemaId, schema);
    }

    public void resumeExecution(String schemaId, WorkflowSchema schema) {
        if (schema == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Schema not found: " + schemaId);
        }

        ExecutionRun parentRun = executionRepository != null ?
                executionRepository.getLatestRunBySchemaAndStatus(schemaId, "paused") : null;
        if (parentRun == null) {
            log.warn("No paused run found for schema: {}", schemaId);
            return;
        }

        if (executionRepository != null && executionRepository.hasActiveRun(schemaId)) {
            // If there is an active run, allow resume only when there is no *running* run
            // and the only active run is the paused parent we just fetched. This permits
            // resuming a paused run while still protecting against concurrent running runs.
            ExecutionRun running = executionRepository.getLatestRunBySchemaAndStatus(schemaId, "running");
            if (running != null) {
                log.warn("Schema {} already has an active running run ({}), cannot resume", schemaId, running.getId());
                return;
            }
        }

        // Get parent node executions
        List<NodeExecution> parentNodeExecs = executionRepository != null ?
                executionRepository.getNodeExecutionsByRun(parentRun.getId()) : List.of();

        // Create child run
        String childRunId = UUID.randomUUID().toString();
        ExecutionRun childRun = new ExecutionRun();
        childRun.setId(childRunId);
        childRun.setSchemaId(schemaId);
        childRun.setStatus("running");
        childRun.setMode("EXECUTE");
        childRun.setResumesFrom(parentRun.getId());
        childRun.setStartedAt(Instant.now().toString());
        childRun.setUpdatedAt(Instant.now().toString());
        if (executionRepository != null) {
            executionRepository.createRun(childRun);
        }

        // Compute config hashes for current schema nodes
        List<Node> schemaNodes = schema.getNodes();
        if (schemaNodes == null) schemaNodes = new ArrayList<>();

        Map<String, String> currentConfigHashes = new HashMap<>();
        for (Node node : schemaNodes) {
            currentConfigHashes.put(node.getId(), computeConfigHash(node, schema));
        }

        // Reset node statuses and create NodeExecution records
        for (Node node : schemaNodes) {
            String currentHash = currentConfigHashes.get(node.getId());
            NodeExecution matched = parentNodeExecs.stream()
                    .filter(ne -> ne.getNodeId().equals(node.getId()))
                    .filter(ne -> "completed".equals(ne.getStatus()))
                    .filter(ne -> currentHash.equals(ne.getConfigHash()))
                    .findFirst().orElse(null);

            NodeExecution newNodeExec = new NodeExecution();
            newNodeExec.setId(UUID.randomUUID().toString());
            newNodeExec.setRunId(childRunId);
            newNodeExec.setNodeId(node.getId());
            newNodeExec.setNodeName(node.getName());
            newNodeExec.setNodeType(node.getType());
            newNodeExec.setStartedAt(Instant.now().toString());

            if (matched != null) {
                newNodeExec.setStatus("skipped");
                newNodeExec.setOutputSummary(matched.getOutputSummary());
                newNodeExec.setConfigHash(currentHash);
                newNodeExec.setCompletedAt(Instant.now().toString());
                node.setStatus(Node.NodeStatus.COMPLETED);
            } else {
                newNodeExec.setStatus("pending");
                newNodeExec.setConfigHash(currentHash);
                node.setStatus(Node.NodeStatus.IDLE);
            }
            if (executionRepository != null) {
                executionRepository.createNodeExecution(newNodeExec);
            }

            // Add skipped node results to nodeExecutor so downstream nodes can read them
            if (matched != null && matched.getOutputSummary() != null) {
                nodeExecutor.getNodeResults()
                        .computeIfAbsent(schemaId, k -> new ConcurrentHashMap<>())
                        .put(node.getId(), matched.getOutputSummary());
            }
        }

        log.info("Resuming schema {}: parentRun={}, childRun={}, {} nodes",
                schemaId, parentRun.getId(), childRunId, schemaNodes.size());

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        cancelFlags.put(schemaId, cancelFlag);
        nodeExecutor.setCurrentRunId(schemaId, childRunId);
        CompletableFuture<?> future = CompletableFuture.runAsync(
                () -> executeWorkflow(schema, cancelFlag, childRunId), executionExecutor);
        runningExecutions.put(schemaId, future);
    }

    public List<NodeExecution> getExecutionNodes(String runId) {
        if (executionRepository == null) return List.of();
        return executionRepository.getNodeExecutionsByRun(runId);
    }

    // ────────────────────────── History ──────────────────────────

    public List<ExecutionRecord> getExecutionHistory(String schemaId) {
        synchronized (executionHistoryLock) {
            return executionHistory.stream()
                    .filter(r -> schemaId.equals(r.getSchemaId()))
                    .sorted((a, b) -> Long.compare(b.getStartTime(), a.getStartTime()))
                    .limit(50)
                    .collect(Collectors.toList());
        }
    }

    public List<ExecutionRecord> getAllExecutionHistory() {
        synchronized (executionHistoryLock) {
            return executionHistory.stream()
                    .sorted((a, b) -> Long.compare(b.getStartTime(), a.getStartTime()))
                    .limit(50)
                    .collect(Collectors.toList());
        }
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
        // Store feedback in the node result map for the review node to pick up
        String feedbackKey = executionId + ":" + nodeId + ":feedback";
        nodeExecutor.getNodeResults().computeIfAbsent(executionId, k -> new ConcurrentHashMap<>())
                .put(feedbackKey, feedback);

        // Store feedback history
        String historyKey = executionId + ":" + nodeId + ":feedbackHistory";
        nodeExecutor.getNodeResults().computeIfAbsent(executionId, k -> new ConcurrentHashMap<>())
                .put(historyKey, history != null ? history.toString() : "[]");

        // Find the paused node and resume it from AWAITING_APPROVAL → RUNNING
        WorkflowSchema schema = schemaRepository.findById(executionId);
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

        synchronized (executionHistoryLock) {
            executionHistory.add(record);
            if (executionHistory.size() > MAX_HISTORY) {
                executionHistory.subList(0, executionHistory.size() - MAX_HISTORY).clear();
            }
        }
    }

    private void executeWorkflow(WorkflowSchema schema, AtomicBoolean cancelFlag, String runId) {
        log.info("Выполнение схемы: {}", schema.getName());
        long startTime = System.currentTimeMillis();
        long workflowStartTime = startTime;

        Map<String, String> conditionResults = nodeExecutor.getConditionResults();
        conditionResults.keySet().removeIf(k -> k.startsWith(schema.getId() + ":"));
        // Preserve injected results when resuming: only clear previous nodeResults if none present
        Map<String, String> existingResults = nodeExecutor.getNodeResults().get(schema.getId());
        if (existingResults == null || existingResults.isEmpty()) {
            nodeExecutor.getNodeResults().remove(schema.getId());
        } else {
            log.debug("Preserving existing node results for schema {} (possible resume) - {} entries", schema.getId(), existingResults.size());
        }
        nodeFailureCounts.put(schema.getId(), new ConcurrentHashMap<>());

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schema.getId(), "system", "STARTED", 0, "Выполнение начато");
            webSocketHandler.sendLog(schema.getId(), "info", "Выполнение схемы начато: " + schema.getName(), null);
        }

        // Создаём задачу в плане, если схема с targetPath (режим приложения)
        Task executionTask = null;
        if (schema.getTargetPath() != null && !schema.getTargetPath().isBlank()) {
            try {
                String wsId = schema.getWorkspaceId() != null ? schema.getWorkspaceId() : "default";
                executionTask = planService.createTaskForExecution(
                        wsId, schema.getId(), schema.getName());
                log.info("Создана задача выполнения для схемы с targetPath: {} (taskId={})",
                        schema.getTargetPath(), executionTask.getId());
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schema.getId(), "info",
                            "Создана задача отслеживания выполнения", null);
                }
            } catch (Exception e) {
                log.warn("Не удалось создать задачу выполнения: {}", e.getMessage());
            }
        }

        List<List<Node>> levels = getExecutionLevels(schema);
        Set<String> skippedNodes = computeSkippedNodes(schema, conditionResults);

        int totalNodes = levels.stream().mapToInt(List::size).sum();
        int completedCount = 0;
        int waveNum = 0;

        for (List<Node> level : levels) {
            if (webSocketHandler != null) {
                List<String> nodeIds = level.stream().map(Node::getId).toList();
                webSocketHandler.sendWaveUpdate(schema.getId(), waveNum++, nodeIds, "pending");
            }

            if (cancelFlag.get()) break;

            List<Node> executable = level.stream()
                    .filter(node -> !skippedNodes.contains(node.getId()))
                    .filter(node -> node.getStatus() != Node.NodeStatus.BLOCKED && node.getStatus() != Node.NodeStatus.FAILED)
                    .filter(node -> !cancelFlag.get())
                    .toList();

            for (Node node : level) {
                if (skippedNodes.contains(node.getId())) {
                    log.info("Пропуск узла (невыполненная ветка условия): {}", node.getId());
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schema.getId(), "info",
                                "Пропуск узла (невыполненная ветка): " + node.getName(), node.getId());
                    }
                }
            }

            if (executable.isEmpty()) continue;

            if (webSocketHandler != null) {
                List<String> execIds = executable.stream().map(Node::getId).toList();
                webSocketHandler.sendWaveUpdate(schema.getId(), waveNum - 1, execIds, "running");
            }

            // Emit step events for each node in this level
            if (webSocketHandler != null) {
                for (Node node : level) {
                    String blockType = node.getType() != null ? node.getType() : "unknown";
                    String label = node.getName() != null ? node.getName() : "Untitled";
                    webSocketHandler.sendStep(schema.getId(), completedCount, node.getId(), blockType, label, "running", "", 0);
                }
            }

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Node node : executable) {
                log.info("Начало выполнения узла: {} ({})", node.getId(), node.getName());
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schema.getId(), "info",
                            "Начало выполнения узла: " + node.getName(), node.getId());
                }

                final long nodeStartTime = System.currentTimeMillis();
                futures.add(CompletableFuture.runAsync(() -> {
                    String resolvedModel = resolveModel(
                            node.getData() != null ? node.getData().getModel() : null, schema);
                    nodeExecutor.executeNode(node, schema.getId(), cancelFlag, com.agent.orchestrator.model.ExecutionMode.EXECUTE, resolvedModel);
                    long nodeTime = System.currentTimeMillis() - nodeStartTime;
                    log.info("Узел завершен: {} ({}) - {}мс", node.getId(), node.getName(), nodeTime);
                    if (webSocketHandler != null) {
                        webSocketHandler.sendNodeTime(schema.getId(), node.getId(), nodeTime);
                    }
                }, executionExecutor));
            }

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                // Save checkpoint after each wave
                if (executionRepository != null) {
                    saveCheckpoint(schema, runId, waveNum - 1);
                }
                if (webSocketHandler != null) {
                    List<String> execIds = executable.stream().map(Node::getId).toList();
                    webSocketHandler.sendWaveUpdate(schema.getId(), waveNum - 1, execIds, "completed");
                }
            } catch (Exception e) {
                log.error("Ошибка при параллельном выполнении уровня: {}", e.getMessage());
            }

            completedCount += executable.size();

            long currentTime = System.currentTimeMillis() - startTime;
            double nodesPerSecond = currentTime > 0 ? (double) completedCount / (currentTime / 1000.0) : 0;
            if (webSocketHandler != null) {
                webSocketHandler.sendMetrics(schema.getId(), totalNodes, completedCount, currentTime, nodesPerSecond);
                for (Node node : executable) {
                    webSocketHandler.sendLog(schema.getId(), "success",
                            "Узел завершен: " + node.getName(), node.getId());
                }
            }
        }

        int nodesCompleted = (int) schema.getNodes().stream()
                .filter(n -> n.getStatus() == Node.NodeStatus.COMPLETED)
                .count();
        long totalTime = System.currentTimeMillis() - startTime;

        if (cancelFlag.get()) {
            if (webSocketHandler != null) {
                webSocketHandler.sendError(schema.getId(), "system", "Выполнение остановлено");
                webSocketHandler.sendLog(schema.getId(), "warning", "Выполнение схемы остановлено пользователем", null);
            }
            log.warn("Выполнение схемы отменено: {}", schema.getName());
            if (executionRepository != null) {
                executionRepository.updateRunStatus(runId, "cancelled", "Cancelled by user");
            }
            recordExecution(schema, workflowStartTime, totalTime, totalNodes, nodesCompleted, "cancelled");
        } else {
            if (webSocketHandler != null) {
                webSocketHandler.sendComplete(schema.getId(), totalTime, nodesCompleted);
                double finalNodesPerSecond = totalTime > 0 ? (double) nodesCompleted / (totalTime / 1000.0) : 0;
                webSocketHandler.sendMetrics(schema.getId(), nodesCompleted, nodesCompleted, totalTime, finalNodesPerSecond);
                webSocketHandler.sendLog(schema.getId(), "success",
                        "Выполнение схемы завершено: " + totalTime + "мс, узлов: " + nodesCompleted, null);
                Map<String, Object> payload = new HashMap<>();
                payload.put("status", "completed");
                payload.put("totalTime", totalTime);
                payload.put("nodesCompleted", nodesCompleted);
                webSocketHandler.sendLiveUpdate(schema.getId(), "CUSTOM", payload);
            }
            log.info("Выполнение схемы завершено: {} ({}мс, {}/{} узлов)", schema.getName(), totalTime, nodesCompleted, totalNodes);

            // Завершаем задачу в плане, если была создана (схема с targetPath)
            if (executionTask != null) {
                try {
                    List<Task.GeneratedFile> generatedFiles = new ArrayList<>();
                    String prefix = schema.getId() + ":";
                    for (java.util.Map.Entry<String, String> entry : nodeExecutor.getOutputFileRegistry().entrySet()) {
                        if (entry.getKey().startsWith(prefix)) {
                            generatedFiles.add(new Task.GeneratedFile(entry.getValue(),
                                    "Сгенерированный файл из узла " + entry.getKey().substring(prefix.length())));
                        }
                    }
                    String wsId = schema.getWorkspaceId() != null ? schema.getWorkspaceId() : "default";
                    planService.completeTaskForExecution(executionTask.getId(), wsId, generatedFiles);
                    log.info("Задача выполнения завершена: {} ({} файлов)", executionTask.getId(), generatedFiles.size());
                } catch (Exception e) {
                    log.warn("Не удалось завершить задачу выполнения: {}", e.getMessage());
                }
            }

            if (executionRepository != null) {
                executionRepository.updateRunCompleted(runId, "completed", 0, 0.0);
            }
            recordExecution(schema, workflowStartTime, totalTime, totalNodes, nodesCompleted, "completed");
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
            log.error("Ошибка вычисления config hash: {}", e.getMessage());
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
            if (executionRepository != null) {
                executionRepository.saveCheckpoint(cp);
            }
        } catch (Exception e) {
            log.warn("Ошибка сохранения чекпоинта: {}", e.getMessage());
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
        log.warn("resolveModel: all fallbacks exhausted, returning null");
        return null;
    }

    // ────────────────────────── Prompt-to-Schema Generation ──────────────────────────

    private static final String PROMPT_TO_SCHEMA_SYSTEM = """
            You are a workflow architect. The user will describe an application or workflow they want to build.
            Design a complete Axolotl workflow schema with REAL, specific prompts and configuration — NOT empty placeholders.

            RULES:
            - Every agent node MUST have detailed, specific systemPrompt and userPrompt (min 2-3 sentences each)
            - Choose appropriate models: use "minimax-max" for complex tasks, "minimax-m2.5-free" for simple ones
            - Include realistic enabledTools and toolPermissions for agent nodes that need file/code access
            - Create a logical flow: typically source -> (analyze/read) -> (implement/write) -> (verify/test) -> output
            - Position nodes with x increments of 350, starting at x=100, y=200
            - Each node needs a unique id: n1, n2, n3, etc.
            - Include at least 3-7 nodes for non-trivial workflows
            - Add a planExplanation describing what each node does and why

            Available node types:
            - "source" — provides initial data/input (has sourceData field)
            - "agent" — LLM-powered node (has systemPrompt, userPrompt, model, agentType, enabledTools, toolPermissions)
            - "output" — writes result to file/log/memory
            - "condition" — branches based on JS expression
            - "loop" — iterates with a condition
            - "memory" — stores/retrieves from memory
            - "guardrail" — validates/safety-checks output
            - "transform" — transforms data between nodes

            Available tools: file_read, file_write, directory_read, grep, git, bash, memory_read, memory_write, memory_search, web_search, web_fetch

            Agent types: coder, assistant, researcher, reviewer

            Respond ONLY with valid JSON — no markdown fences, no commentary:
            {
              "name": "Descriptive Schema Name",
              "description": "What this workflow accomplishes",
              "nodes": [
                {
                  "id": "n1",
                  "type": "source",
                  "name": "Descriptive name",
                  "position": {"x": 100, "y": 200},
                  "data": {
                    "sourceData": "actual input content or instructions"
                  }
                },
                {
                  "id": "n2",
                  "type": "agent",
                  "name": "Descriptive name",
                  "position": {"x": 450, "y": 200},
                  "data": {
                    "systemPrompt": "Detailed specific system prompt...",
                    "userPrompt": "Detailed specific user prompt...",
                    "model": "minimax-max",
                    "agentType": "coder",
                    "enabledTools": ["file_read", "file_write", "bash"],
                    "maxToolCalls": 50,
                    "toolPermissions": [
                      {"toolId": "file_read", "allowedPaths": ["/full/path/**"], "enabled": true},
                      {"toolId": "file_write", "allowedPaths": ["/full/path/**"], "enabled": true}
                    ]
                  }
                }
              ],
              "edges": [
                {"source": "n1", "target": "n2"}
              ],
              "planExplanation": "Markdown explanation of the workflow design"
            }
            """;

    public Map<String, Object> generateSchemaFromPrompt(String prompt, String model) {
        Map<String, Object> result = new HashMap<>();
        try {
            String resolvedModel = model;
            if (resolvedModel == null || resolvedModel.isBlank()) {
                resolvedModel = settingsService.getGlobalDefaultModel();
            }
            if (resolvedModel == null || resolvedModel.isBlank()) {
                resolvedModel = "minimax-max";
            }

            log.info("Generating schema from prompt (model={}): {}", resolvedModel, prompt.substring(0, Math.min(100, prompt.length())));

            String llmResponse = llmService.chat(resolvedModel, PROMPT_TO_SCHEMA_SYSTEM, prompt, null);

            if (llmResponse == null || llmResponse.isBlank()) {
                result.put("success", false);
                result.put("error", "LLM returned empty response");
                return result;
            }

            String jsonStr = llmResponse.trim();
            if (jsonStr.startsWith("```")) {
                jsonStr = jsonStr.replaceFirst("^```\\w*\\n?", "").replaceFirst("\\n?```$", "");
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root;
            try {
                root = mapper.readTree(jsonStr);
            } catch (Exception e) {
                result.put("success", false);
                result.put("error", "Failed to parse LLM response as JSON: " + e.getMessage());
                result.put("raw", llmResponse);
                return result;
            }

            WorkflowSchema schema = new WorkflowSchema();
            schema.setName(root.has("name") ? root.get("name").asText() : "Generated Schema");
            schema.setDescription(root.has("description") ? root.get("description").asText() : "");
            schema.setVersion("1.0");

            List<Node> nodes = new ArrayList<>();
            if (root.has("nodes")) {
                for (JsonNode n : root.get("nodes")) {
                    Node schemaNode = new Node();
                    schemaNode.setId(n.has("id") ? n.get("id").asText() : UUID.randomUUID().toString());
                    schemaNode.setType(n.has("type") ? n.get("type").asText() : "agent");
                    schemaNode.setName(n.has("name") ? n.get("name").asText() : "Node");
                    if (n.has("position")) {
                        Node.Position pos = new Node.Position();
                        pos.setX(n.get("position").has("x") ? n.get("position").get("x").asInt() : 100);
                        pos.setY(n.get("position").has("y") ? n.get("position").get("y").asInt() : 200);
                        schemaNode.setPosition(pos);
                    }
                    if (n.has("data")) {
                        Node.NodeData data = new Node.NodeData();
                        JsonNode d = n.get("data");
                        if (d.has("userPrompt")) data.setUserPrompt(d.get("userPrompt").asText());
                        if (d.has("systemPrompt")) data.setSystemPrompt(d.get("systemPrompt").asText());
                        if (d.has("model")) data.setModel(d.get("model").asText());
                        if (d.has("agentType")) data.setAgentType(d.get("agentType").asText());
                        if (d.has("sourceData")) data.setSourceData(d.get("sourceData").asText());
                        if (d.has("enabledTools") && d.get("enabledTools").isArray()) {
                            List<String> tools = new ArrayList<>();
                            for (JsonNode t : d.get("enabledTools")) tools.add(t.asText());
                            data.setEnabledTools(tools);
                        }
                        if (d.has("maxToolCalls")) data.setMaxToolCalls(d.get("maxToolCalls").asInt());
                        if (d.has("toolPermissions") && d.get("toolPermissions").isArray()) {
                            List<ToolPermission> tps = new ArrayList<>();
                            for (JsonNode tpNode : d.get("toolPermissions")) {
                                ToolPermission tp = new ToolPermission();
                                if (tpNode.has("toolId")) tp.setToolId(tpNode.get("toolId").asText());
                                if (tpNode.has("enabled")) tp.setEnabled(tpNode.get("enabled").asBoolean());
                                if (tpNode.has("allowedPaths") && tpNode.get("allowedPaths").isArray()) {
                                    Set<String> paths = new HashSet<>();
                                    for (JsonNode p : tpNode.get("allowedPaths")) paths.add(p.asText());
                                    tp.setAllowedPaths(paths);
                                }
                                tps.add(tp);
                            }
                            data.setToolPermissions(tps);
                        }
                        schemaNode.setData(data);
                    }
                    nodes.add(schemaNode);
                }
            }
            schema.setNodes(nodes);

            List<Edge> edges = new ArrayList<>();
            if (root.has("edges")) {
                for (JsonNode e : root.get("edges")) {
                    Edge edge = new Edge();
                    edge.setId(UUID.randomUUID().toString());
                    edge.setSource(e.has("source") ? e.get("source").asText() : "");
                    edge.setTarget(e.has("target") ? e.get("target").asText() : "");
                    edges.add(edge);
                }
            }
            schema.setEdges(edges);

            String id = UUID.randomUUID().toString();
            schema.setId(id);
            schema.setCreatedAt(Instant.now().toString());
            schema.setUpdatedAt(Instant.now().toString());
            schemaRepository.save(schema);

            log.info("Generated schema '{}' with {} nodes, {} edges (ID: {})", schema.getName(), nodes.size(), edges.size(), id);

            result.put("success", true);
            result.put("schema", schema);
            if (root.has("planExplanation")) {
                result.put("planExplanation", root.get("planExplanation").asText());
            }
        } catch (Exception e) {
            log.error("Failed to generate schema from prompt: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * Generate nodes and edges for an existing schema from a natural language prompt.
     * Reuses PROMPT_TO_SCHEMA_SYSTEM but constrains LLM to return only nodes/edges.
     * Saves the new nodes/edges onto the existing schema.
     */
    public Map<String, Object> generateNodes(String schemaId, String prompt, String model) {
        Map<String, Object> result = new HashMap<>();
        try {
            // 1. Load existing schema
            WorkflowSchema schema = schemaRepository.findById(schemaId);
            if (schema == null) {
                result.put("success", false);
                result.put("error", "Schema not found: " + schemaId);
                return result;
            }

            // 2. Resolve model
            String resolvedModel = model;
            if (resolvedModel == null || resolvedModel.isBlank()) {
                resolvedModel = settingsService.getGlobalDefaultModel();
            }
            if (resolvedModel == null || resolvedModel.isBlank()) {
                resolvedModel = "minimax-max";
            }

            log.info("Generating nodes for schema '{}' (model={}): {}", schemaId, resolvedModel, prompt.substring(0, Math.min(100, prompt.length())));

            // 3. Build system prompt — constrain to ONLY return nodes/edges
            String nodeSystemPrompt = PROMPT_TO_SCHEMA_SYSTEM + "\n\nIMPORTANT: The schema already exists. Return ONLY a JSON object with \"nodes\" and \"edges\" fields. Do NOT include \"name\", \"description\", \"version\", \"planExplanation\", or any other top-level fields. Only the nodes and edges arrays.";

            // 4. Call LLM
            String llmResponse = llmService.chat(resolvedModel, nodeSystemPrompt, prompt, null);

            if (llmResponse == null || llmResponse.isBlank()) {
                result.put("success", false);
                result.put("error", "LLM returned empty response");
                return result;
            }

            // 5. Parse JSON (strip markdown code fences if present)
            String jsonStr = llmResponse.trim();
            if (jsonStr.startsWith("```")) {
                jsonStr = jsonStr.replaceFirst("^```\\w*\\n?", "").replaceFirst("\\n?```$", "");
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root;
            try {
                root = mapper.readTree(jsonStr);
            } catch (Exception e) {
                result.put("success", false);
                result.put("error", "Failed to parse LLM response as JSON: " + e.getMessage());
                result.put("raw", llmResponse);
                return result;
            }

            // 6. Extract nodes
            List<Node> nodes = new ArrayList<>();
            if (root.has("nodes")) {
                for (JsonNode n : root.get("nodes")) {
                    Node schemaNode = new Node();
                    schemaNode.setId(n.has("id") ? n.get("id").asText() : UUID.randomUUID().toString());
                    schemaNode.setType(n.has("type") ? n.get("type").asText() : "agent");
                    schemaNode.setName(n.has("name") ? n.get("name").asText() : "Node");
                    if (n.has("position")) {
                        Node.Position pos = new Node.Position();
                        pos.setX(n.get("position").has("x") ? n.get("position").get("x").asInt() : 100);
                        pos.setY(n.get("position").has("y") ? n.get("position").get("y").asInt() : 200);
                        schemaNode.setPosition(pos);
                    }
                    if (n.has("data")) {
                        Node.NodeData data = new Node.NodeData();
                        JsonNode d = n.get("data");
                        if (d.has("userPrompt")) data.setUserPrompt(d.get("userPrompt").asText());
                        if (d.has("systemPrompt")) data.setSystemPrompt(d.get("systemPrompt").asText());
                        if (d.has("model")) data.setModel(d.get("model").asText());
                        if (d.has("agentType")) data.setAgentType(d.get("agentType").asText());
                        if (d.has("sourceData")) data.setSourceData(d.get("sourceData").asText());
                        if (d.has("enabledTools") && d.get("enabledTools").isArray()) {
                            List<String> tools = new ArrayList<>();
                            for (JsonNode t : d.get("enabledTools")) tools.add(t.asText());
                            data.setEnabledTools(tools);
                        }
                        if (d.has("maxToolCalls")) data.setMaxToolCalls(d.get("maxToolCalls").asInt());
                        schemaNode.setData(data);
                    }
                    nodes.add(schemaNode);
                }
            }

            if (nodes.isEmpty()) {
                result.put("success", false);
                result.put("error", "No nodes generated. Try a more specific description.");
                return result;
            }

            // 7. Extract edges
            List<Edge> edges = new ArrayList<>();
            if (root.has("edges")) {
                for (JsonNode e : root.get("edges")) {
                    Edge edge = new Edge();
                    edge.setId(UUID.randomUUID().toString());
                    edge.setSource(e.has("source") ? e.get("source").asText() : "");
                    edge.setTarget(e.has("target") ? e.get("target").asText() : "");
                    edges.add(edge);
                }
            }

            // 8. Update existing schema with new nodes/edges
            schema.setNodes(nodes);
            schema.setEdges(edges);
            schema.setUpdatedAt(Instant.now().toString());
            schemaRepository.save(schema);

            log.info("Generated {} nodes and {} edges for schema '{}'", nodes.size(), edges.size(), schemaId);

            result.put("success", true);
            result.put("schema", schema);

        } catch (Exception e) {
            log.error("Failed to generate nodes for schema {}: {}", schemaId, e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }
}
