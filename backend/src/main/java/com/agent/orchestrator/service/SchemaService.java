package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.ExecutionMode;
import com.agent.orchestrator.model.Plan;
import io.micrometer.core.instrument.Timer;
import com.agent.orchestrator.model.ExecutionRecord;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.Priority;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.model.Tool;
import com.agent.orchestrator.model.ToolPermission;
import com.agent.orchestrator.model.Tool.ToolResult;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.graalvm.polyglot.Context;

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
    private final LlmService llmService;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final MemPalaceClient memPalaceClient;
    private final PlanService planService;
    private final SettingsService settingsService;
    private final ToolExecutor toolExecutor;
    private final MetricsService metricsService;
    private final Map<String, CompletableFuture<?>> runningExecutions = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final Map<String, String> conditionResults = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> nodeResults = new ConcurrentHashMap<>();
    private final List<ExecutionRecord> executionHistory = Collections.synchronizedList(new ArrayList<>());
    private final Object executionHistoryLock = new Object();
    private static final int MAX_HISTORY = 100;
    private static final int MAX_NODE_RESTARTS = 3;
    private final ExecutorService executionExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors());

    // Convergence monitoring: track consecutive failures per node per execution
    private final Map<String, Map<String, Integer>> nodeFailureCounts = new ConcurrentHashMap<>();
    private TransformService transformService;

    public SchemaService(Neo4jSchemaRepository schemaRepository,
            LlmService llmService,
            ExecutionWebSocketHandler webSocketHandler,
            MemPalaceClient memPalaceClient,
            PlanService planService,
            SettingsService settingsService,
            ToolExecutor toolExecutor,
            MetricsService metricsService,
            TransformService transformService) {
        this.schemaRepository = schemaRepository;
        this.llmService = llmService;
        this.webSocketHandler = webSocketHandler;
        this.memPalaceClient = memPalaceClient;
        this.planService = planService;
        this.settingsService = settingsService;
        this.toolExecutor = toolExecutor;
        this.toolExecutor.setWebSocketHandler(webSocketHandler);
        this.toolExecutor.setLlmService(llmService);
        this.metricsService = metricsService;
        this.transformService = transformService;
    }

    @jakarta.annotation.PostConstruct
    void init() {
        initDemoSchema();
    }

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
        // Fallback: return all schemas (single-user or admin mode)
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
        // Sanitize: remove null entries from nodes/edges lists
        if (schema.getNodes() != null) {
            schema.setNodes(schema.getNodes().stream().filter(n -> n != null && n.getId() != null).toList());
        }
        if (schema.getEdges() != null) {
            schema.setEdges(schema.getEdges().stream().filter(e -> e != null && e.getId() != null).toList());
        }
        schemaRepository.save(schema);

        // Schema versioning: save each change to MemPalace
        if (memPalaceClient.isEnabled()) {
            int nodeCount = schema.getNodes() != null ? schema.getNodes().size() : 0;
            int edgeCount = schema.getEdges() != null ? schema.getEdges().size() : 0;
            String nodeTypes = !schema.getNodes().isEmpty()
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
        log.info("   - Узлов: {}", schema.getNodes() != null ? schema.getNodes().size() : 0);
        log.info("   - Связей: {}", schema.getEdges() != null ? schema.getEdges().size() : 0);
        return schema;
    }

    public void deleteSchema(String id) {
        cancelExecution(id);
        schemaRepository.delete(id);
        log.info("Удалена схема: {}", id);
    }

    public String exportToMermaid(String id) {
        WorkflowSchema schema = schemaRepository.findById(id);
        if (schema == null)
            return "";

        StringBuilder mermaid = new StringBuilder();
        mermaid.append("```mermaid\ngraph TD\n");

        if (schema.getNodes() != null) {
            for (Node node : schema.getNodes()) {
                String shape = getNodeShape(node.getType());
                String label = node.getName().replace("\"", "\\\"");
                mermaid.append(String.format("    %s%s[\"%s\"]%n", node.getId(), shape, label));
            }
        }

        if (schema.getEdges() != null) {
            for (Edge edge : schema.getEdges()) {
                String edgeStyle = getEdgeStyle(edge.getType());
                mermaid.append(String.format("    %s %s %s%n", edge.getSource(), edgeStyle, edge.getTarget()));
            }
        }

        mermaid.append("```");
        return mermaid.toString();
    }

    public String exportToPython(String id) {
        WorkflowSchema schema = schemaRepository.findById(id);
        if (schema == null) return "";

        StringBuilder py = new StringBuilder();
        py.append("#!/usr/bin/env python3\n");
        py.append("# Axolotl Schema: ").append(schema.getName()).append("\n");
        py.append("# Generated by Axolotl\n\n");
        py.append("import requests\n");
        py.append("import json\n");
        py.append("import sys\n");
        py.append("from typing import Dict, Any, List\n\n");

        // Build execution levels (topological sort)
        List<List<Node>> levels = computeExecutionLevels(schema);

        // Variables to hold results
        py.append("# Results storage\n");
        py.append("results: Dict[str, str] = {}\n\n");

        // Helper functions
        py.append("def call_ollama(prompt: str, system_prompt: str = \"\", model: str = \"gemma4:e2b\") -> str:\n");
        py.append("    \"\"\"Call Ollama LLM\"\"\"\n");
        py.append("    url = \"http://localhost:11434/api/chat\"\n");
        py.append("    messages = []\n");
        py.append("    if system_prompt:\n");
        py.append("        messages.append({\"role\": \"system\", \"content\": system_prompt})\n");
        py.append("    messages.append({\"role\": \"user\", \"content\": prompt})\n");
        py.append("    resp = requests.post(url, json={\"model\": model, \"messages\": messages, \"stream\": False})\n");
        py.append("    resp.raise_for_status()\n");
        py.append("    return resp.json()[\"message\"][\"content\"]\n\n");

        py.append("def interpolate(text: str, results: Dict[str, str]) -> str:\n");
        py.append("    \"\"\"Replace {{variable}} placeholders\"\"\"\n");
        py.append("    import re\n");
        py.append("    def replacer(m):\n");
        py.append("        key = m.group(1).strip()\n");
        py.append("        return results.get(key, m.group(0))\n");
        py.append("    return re.sub(r'\\{\\{(.+?)\\}\\}', replacer, text)\n\n");

        // Generate code for each level
        for (int i = 0; i < levels.size(); i++) {
            py.append("# === Level ").append(i).append(" (parallel) ===\n");
            for (Node node : levels.get(i)) {
                generatePythonForNode(py, node, schema);
                py.append("\n");
            }
        }

        // Final output
        py.append("\n# === Print final results ===\n");
        py.append("print(\"\\n--- Execution Complete ---\")\n");
        py.append("for name, result in results.items():\n");
        py.append("    print(f\"{name}: {result[:200]}\")\n");

        return py.toString();
    }

    private void generatePythonForNode(StringBuilder py, Node node, WorkflowSchema schema) {
        String varName = node.getName().replaceAll("[^a-zA-Z0-9_]", "_");

        switch (node.getType()) {
            case "source":
                String sourceData = node.getData() != null && node.getData().getSourceData() != null
                        ? escapePythonString(node.getData().getSourceData()) : "";
                py.append("results[\"").append(varName).append("\"] = \"").append(sourceData).append("\"\n");
                break;

            case "agent":
                String userPrompt = node.getData() != null && node.getData().getUserPrompt() != null
                        ? escapePythonString(node.getData().getUserPrompt()) : "";
                String systemPrompt = node.getData() != null && node.getData().getSystemPrompt() != null
                        ? escapePythonString(node.getData().getSystemPrompt()) : "";
                String model = node.getData() != null && node.getData().getModel() != null
                        ? escapePythonString(node.getData().getModel()) : "gemma4:e2b";

                py.append("# Agent: ").append(node.getName()).append("\n");
                py.append("print(\"Running agent: ").append(node.getName()).append("\")\n");
                py.append("prompt_").append(varName).append(" = interpolate(\"").append(userPrompt).append("\", results)\n");
                if (!systemPrompt.isEmpty()) {
                    py.append("system_").append(varName).append(" = interpolate(\"").append(systemPrompt).append("\", results)\n");
                }
                py.append("results[\"").append(varName).append("\"] = call_ollama(");
                py.append("prompt=prompt_").append(varName).append(", ");
                if (!systemPrompt.isEmpty()) {
                    py.append("system_prompt=system_").append(varName).append(", ");
                }
                py.append("model=\"").append(model).append("\")\n");
                break;

            case "condition":
                String condition = node.getData() != null && node.getData().getCondition() != null
                        ? node.getData().getCondition() : "true";
                py.append("# Condition: ").append(node.getName()).append("\n");
                py.append("results[\"").append(varName).append("\"] = str(").append(condition).append(")\n");
                break;

            case "output":
                py.append("# Output: ").append(node.getName()).append("\n");
                py.append("print(f\"Output: {results}\")\n");
                break;

            case "memory":
                String searchQuery = node.getData() != null && node.getData().getSourceData() != null
                        ? escapePythonString(node.getData().getSourceData()) : node.getName();
                py.append("# Memory search: ").append(node.getName()).append("\n");
                py.append("results[\"").append(varName).append("\"] = \"Memory search: ").append(searchQuery).append(" (stub)\"\n");
                break;

            default:
                py.append("# ").append(node.getType()).append(": ").append(node.getName()).append(" (not implemented in Python export)\n");
                py.append("results[\"").append(varName).append("\"] = \"\"\n");
                break;
        }
    }

    private String escapePythonString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private List<List<Node>> computeExecutionLevels(WorkflowSchema schema) {
        List<List<Node>> levels = new ArrayList<>();
        if (schema.getNodes() == null || schema.getNodes().isEmpty()) return levels;

        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adj = new HashMap<>();
        for (Node n : schema.getNodes()) {
            inDegree.put(n.getId(), 0);
            adj.put(n.getId(), new ArrayList<>());
        }
        if (schema.getEdges() != null) {
            for (Edge e : schema.getEdges()) {
                adj.get(e.getSource()).add(e.getTarget());
                inDegree.merge(e.getTarget(), 1, Integer::sum);
            }
        }

        List<String> queue = new ArrayList<>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        Map<String, Node> nodeMap = new HashMap<>();
        for (Node n : schema.getNodes()) nodeMap.put(n.getId(), n);

        while (!queue.isEmpty()) {
            List<Node> level = new ArrayList<>();
            for (String id : queue) level.add(nodeMap.get(id));
            levels.add(level);

            List<String> nextQueue = new ArrayList<>();
            for (String id : queue) {
                for (String neighbor : adj.get(id)) {
                    int newDeg = inDegree.merge(neighbor, -1, Integer::sum);
                    if (newDeg == 0) nextQueue.add(neighbor);
                }
            }
            queue = nextQueue;
        }

        return levels;
    }

    public void executeSchema(String id) {
        executeSchema(id, ExecutionMode.EXECUTE);
    }

    public void executeSchema(String id, ExecutionMode mode) {
        WorkflowSchema schema = schemaRepository.findById(id);
        if (schema == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Schema not found: " + id);
        if (runningExecutions.containsKey(id)) {
            log.warn("Схема уже выполняется: {}", id);
            return;
        }

        if (schema.getNodes() != null) {
            for (Node node : schema.getNodes()) {
                node.setStatus(Node.NodeStatus.IDLE);
            }
        }

        if (metricsService != null) {
            metricsService.recordSchemaExecutionStart();
        }

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        cancelFlags.put(id, cancelFlag);
        Timer.Sample timerSample = metricsService != null ? metricsService.startTimer() : null;
        CompletableFuture<?> future = CompletableFuture.runAsync(
                () -> executeWorkflow(schema, cancelFlag, mode), executionExecutor);
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

        executionHistory.add(record);
        if (executionHistory.size() > MAX_HISTORY) {
            executionHistory.subList(0, executionHistory.size() - MAX_HISTORY).clear();
        }
    }

    private void executeWorkflow(WorkflowSchema schema, AtomicBoolean cancelFlag, ExecutionMode mode) {
        log.info("Выполнение схемы: {} (mode={})", schema.getName(), mode);
        long startTime = System.currentTimeMillis();
        long workflowStartTime = startTime;

        conditionResults.keySet().removeIf(k -> k.startsWith(schema.getId() + ":"));
        nodeResults.remove(schema.getId());
        nodeFailureCounts.put(schema.getId(), new ConcurrentHashMap<>());

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schema.getId(), "system", "STARTED", 0, "Выполнение начато");
            webSocketHandler.sendLog(schema.getId(), "info", "Выполнение схемы начато: " + schema.getName() + " [" + mode + "]", null);
        }

        // Получить уровни выполнения (узлы на одном уровне можно запускать параллельно)
        List<List<Node>> levels = getExecutionLevels(schema);
        Set<String> skippedNodes = computeSkippedNodes(schema);

        int totalNodes = levels.stream().mapToInt(List::size).sum();
        int completedCount = 0;
        int waveNum = 0;

        for (List<Node> level : levels) {
            // Send wave update
            if (webSocketHandler != null) {
                List<String> nodeIds = level.stream().map(Node::getId).toList();
                webSocketHandler.sendWaveUpdate(schema.getId(), waveNum++, nodeIds, "pending");
            }

            if (cancelFlag.get()) break;

            List<Node> executable = level.stream()
                    .filter(node -> !skippedNodes.contains(node.getId()))
                    .filter(node -> node.getStatus() != Node.NodeStatus.BLOCKED)
                    .filter(node -> !cancelFlag.get())
                    .toList();

            // Логирование пропущенных узлов
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

            // Mark wave as running
            if (webSocketHandler != null) {
                List<String> execIds = executable.stream().map(Node::getId).toList();
                webSocketHandler.sendWaveUpdate(schema.getId(), waveNum - 1, execIds, "running");
            }

            // Параллельное выполнение узлов одного уровня
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Node node : executable) {
                log.info("Начало выполнения узла: {} ({})", node.getId(), node.getName());
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schema.getId(), "info",
                            "Начало выполнения узла: " + node.getName(), node.getId());
                }

                final long nodeStartTime = System.currentTimeMillis();
                final ExecutionMode currentMode = mode;
                futures.add(CompletableFuture.runAsync(() -> {
                    executeNode(node, schema.getId(), cancelFlag, currentMode);
                    long nodeTime = System.currentTimeMillis() - nodeStartTime;
                    log.info("Узел завершен: {} ({}) - {}мс", node.getId(), node.getName(), nodeTime);
                    if (webSocketHandler != null) {
                        webSocketHandler.sendNodeTime(schema.getId(), node.getId(), nodeTime);
                    }
                }, executionExecutor));
            }

            // Дождаться завершения всех узлов уровня
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                // Mark wave as completed
                if (webSocketHandler != null) {
                    List<String> execIds = executable.stream().map(Node::getId).toList();
                    webSocketHandler.sendWaveUpdate(schema.getId(), waveNum - 1, execIds, "completed");
                }
            } catch (Exception e) {
                log.error("Ошибка при параллельном выполнении уровня: {}", e.getMessage());
            }

            completedCount += executable.size();

            // Метрики после каждого уровня
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

        // Итоговый подсчёт
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
            recordExecution(schema, workflowStartTime, totalTime, totalNodes, nodesCompleted, "cancelled");
        } else {
            if (webSocketHandler != null) {
                webSocketHandler.sendComplete(schema.getId(), totalTime, nodesCompleted);
                double finalNodesPerSecond = totalTime > 0 ? (double) nodesCompleted / (totalTime / 1000.0) : 0;
                webSocketHandler.sendMetrics(schema.getId(), nodesCompleted, nodesCompleted, totalTime, finalNodesPerSecond);
                webSocketHandler.sendLog(schema.getId(), "success",
                        "Выполнение схемы завершено: " + totalTime + "мс, узлов: " + nodesCompleted, null);
            }
            log.info("Выполнение схемы завершено: {} ({}мс, {}/{} узлов)", schema.getName(), totalTime, nodesCompleted, totalNodes);
            recordExecution(schema, workflowStartTime, totalTime, totalNodes, nodesCompleted, "completed");
        }
    }

    /**
     * Определяет узлы, которые нужно пропустить из-за ветвления условий.
     * Если узел условия дал результат "true", пропускаем узлы, подключённые к порту "false", и наоборот.
     */
    private Set<String> computeSkippedNodes(WorkflowSchema schema) {
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
            // Если условие = true, пропускаем узлы на ветке "false", и наоборот
            if (!conditionResult.equals(edge.getSourcePort())) {
                skipped.add(edge.getTarget());
            }
        }
        // Рекурсивно распространяем пропуск на потомков пропущенных узлов
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

    /**
     * Возвращает узлы сгруппированные по уровням (уровни выполнения).
     * Узлы на одном уровне не имеют зависимостей друг от друга и могут выполняться параллельно.
     */
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

        // Kahn's algorithm с группировкой по уровням
        // Каждый уровень — все узлы, чьи зависимости уже выполнены на предыдущих уровнях
        List<List<Node>> levels = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        // Копия incoming edges для мутации
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
                // Остались только узлы с циклическими зависимостями
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
                // Убрать зависимости от этого узла у потомков
                for (String depNodeId : nodeMap.keySet()) {
                    remainingDeps.get(depNodeId).remove(node.getId());
                }
            }

            levels.add(currentLevel);
        }

        log.info("Уровни выполнения:");
        for (int i = 0; i < levels.size(); i++) {
            log.info("  Уровень {}: {}", i,
                    levels.get(i).stream().map(n -> n.getName()).reduce((a, b) -> a + ", " + b).orElse(""));
        }

        return levels;
    }

    private boolean evaluateCondition(String expression, java.util.Map<String, Object> context) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        try (Context ctx = Context.newBuilder("js")
                .allowAllAccess(false)
                .build()) {
            org.graalvm.polyglot.Value bindings = ctx.getBindings("js");
            context.forEach(bindings::putMember);
            org.graalvm.polyglot.Value result = ctx.eval("js", "Boolean(" + expression + ")");
            return result.asBoolean();
        } catch (Exception e) {
            log.error("Ошибка вычисления условия '{}': {}", expression, e.getMessage());
            return false;
        }
    }

    private java.util.Map<String, Object> collectPredecessorResults(WorkflowSchema schema, String nodeId) {
        java.util.Map<String, Object> results = new java.util.HashMap<>();
        if (schema.getEdges() == null || schema.getNodes() == null) {
            return results;
        }
        Map<String, String> cached = nodeResults.getOrDefault(schema.getId(), Map.of());
        log.info("collectPredecessorResults: cached size for {} = {}", schema.getId(), cached.size());
        
        for (Edge edge : schema.getEdges()) {
            if (nodeId.equals(edge.getTarget())) {
                String sourceId = edge.getSource();
                // Check in-memory cache first, then fall back to node data
                String result = cached.get(sourceId);
                log.info("collectPredecessorResults: edge {}->{}, cached result: {}", sourceId, nodeId, result != null ? result.substring(0, Math.min(100, result.length())) : "null");
                if (result == null) {
                    for (Node n : schema.getNodes()) {
                        if (sourceId.equals(n.getId()) && n.getData() != null && n.getData().getResult() != null) {
                            result = n.getData().getResult();
                            log.info("collectPredecessorResults: fallback to node data for {}, len={}", sourceId, result.length());
                            break;
                        }
                    }
                }
                if (result != null) {
                    String name = schema.getNodes().stream()
                            .filter(n -> sourceId.equals(n.getId()))
                            .map(Node::getName)
                            .findFirst().orElse(sourceId);
                    results.put(name.replaceAll("\\s+", "_"), result);
                }
            }
        }
        return results;
    }

    /**
     * Формирует текстовый блок контекста из результатов предшествующих узлов.
     * Если контекст превышает MAX_CONTEXT_CHARS, сжимает его через LLM.
     */
    private static final int MAX_CONTEXT_CHARS = 4000;

    private String buildContextBlock(Map<String, Object> predecessorResults) {
        if (predecessorResults.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        predecessorResults.forEach((name, value) -> {
            sb.append("[").append(name).append("]: ").append(value).append("\n");
        });
        String context = sb.toString().trim();

        // Context compression: if too long, summarize via LLM
        if (context.length() > MAX_CONTEXT_CHARS) {
            log.info("Сжатие контекста: {} символов → суммаризация", context.length());
            try {
                String summary = llmService.chat("ollama",
                        "Ты компрессор контекста. Сжато передай суть, сохранив ключевые факты, числа, имена.",
                        "Сожми следующий контекст, сохранив ключевые факты:\n\n" + context,
                        null);
                return "[СЖАТЫЙ КОНТЕКСТ]:\n" + summary;
            } catch (Exception e) {
                // Fallback: truncate
                return context.substring(0, MAX_CONTEXT_CHARS) + "\n... [контекст обрезан]";
            }
        }

        return context;
    }

    /**
     * Resolves {{variable}} placeholders in prompts.
     * Supported: {{input}} (first predecessor), {{prev_result}} (immediate predecessor),
     * {{node:Name}} (specific node by name), {{schema_name}}
     */
    private String interpolateVariables(String text, WorkflowSchema schema, Map<String, Object> predecessorResults) {
        if (text == null || !text.contains("{{")) return text;

        // {{input}} — first predecessor result
        String input = predecessorResults.values().stream().findFirst().map(Object::toString).orElse("");
        text = text.replace("{{input}}", input);

        // {{prev_result}} — last predecessor result
        String prevResult = predecessorResults.values().stream()
                .reduce((first, second) -> second).map(Object::toString).orElse("");
        text = text.replace("{{prev_result}}", prevResult);

        // {{node:Name}} — specific node result by name
        if (text.contains("{{node:")) {
            if (schema.getNodes() != null) {
                for (Node n : schema.getNodes()) {
                    String result = n.getData() != null && n.getData().getResult() != null ? n.getData().getResult() : "";
                    String key = "{{node:" + n.getName() + "}}";
                    text = text.replace(key, result);
                }
            }
        }

        // {{schema_name}}
        text = text.replace("{{schema_name}}", schema.getName() != null ? schema.getName() : "");

        return text;
    }

    private void executeNode(Node node, String schemaId, AtomicBoolean cancelFlag, ExecutionMode mode) {
        try {
            if (cancelFlag.get()) {
                node.setStatus(Node.NodeStatus.FAILED);
                return;
            }

            node.setStatus(Node.NodeStatus.RUNNING);

            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 10, "Начало выполнения");
                webSocketHandler.sendLog(schemaId, "info", "Начало выполнения узла [" + mode + "]", node.getId());
            }

            String result = "";

            if ("agent".equals(node.getType())) {
                if (mode == ExecutionMode.DRY_RUN) {
                    result = simulateAgentNode(node, schemaId);
                } else if (mode == ExecutionMode.ANALYZE) {
                    result = analyzeAgentNode(node, schemaId);
                } else {
                    result = executeAgentNode(node, schemaId);
                }

            } else if ("output".equals(node.getType())) {
                result = executeOutputNode(node, schemaId, mode);

            } else if ("command".equals(node.getType())) {
                result = executeCommandNode(node, schemaId);

            } else if ("filewrite".equals(node.getType())) {
                result = executeFileWriteNode(node, schemaId);

            } else if ("source".equals(node.getType())) {
                String sourceType = node.getData() != null && node.getData().getConfig() != null
                        ? (String) node.getData().getConfig().getOrDefault("sourceType", "text") : "text";

                if ("memory".equals(sourceType)) {
                    String query = node.getData() != null && node.getData().getSourceData() != null
                            && !node.getData().getSourceData().isEmpty()
                            ? node.getData().getSourceData() : node.getName();
                    if (webSocketHandler != null) {
                        webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "Поиск в памяти");
                        webSocketHandler.sendLog(schemaId, "info", "Поиск в памяти: " + query, node.getId());
                    }
                    if (memPalaceClient.isEnabled()) {
                        var memResults = memPalaceClient.search(query, null, null, 5);
                        if (memResults.isEmpty()) {
                            result = "Ничего не найдено по запросу: " + query;
                        } else {
                            StringBuilder sb = new StringBuilder();
                            for (var r : memResults) sb.append("- ").append(r.get("content")).append("\n");
                            result = sb.toString().trim();
                        }
                    } else {
                        result = "Память недоступна (MemPalace не подключен)";
                    }
                } else if ("url".equals(sourceType)) {
                    String url = node.getData() != null && node.getData().getConfig() != null
                            ? (String) node.getData().getConfig().getOrDefault("url", "") : "";
                    if (url.isEmpty()) {
                        result = "URL не указан";
                    } else {
                        if (webSocketHandler != null) {
                            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "Загрузка URL");
                            webSocketHandler.sendLog(schemaId, "info", "Загрузка URL: " + url, node.getId());
                        }
                        result = fetchUrlContent(url);
                    }
                } else if ("project".equals(sourceType)) {
                    String projectPath = node.getData() != null && node.getData().getConfig() != null
                            ? (String) node.getData().getConfig().getOrDefault("projectPath", "") : "";
                    if (projectPath.isEmpty()) {
                        result = "Путь к проекту не указан";
                    } else {
                        if (webSocketHandler != null) {
                            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 30, "Чтение структуры проекта");
                            webSocketHandler.sendLog(schemaId, "info", "Чтение проекта: " + projectPath, node.getId());
                        }
                        result = readProjectContext(projectPath, node.getData() != null ? node.getData().getConfig() : null);
                    }
                } else {
                    // "text" or "file" — sourceData already contains the content
                    if (node.getData() != null && node.getData().getSourceData() != null && !node.getData().getSourceData().isEmpty()) {
                        result = node.getData().getSourceData();
                    } else {
                        result = "Данные из источника: " + node.getName();
                    }
                }

            } else if ("condition".equals(node.getType())) {
                String conditionExpr = node.getData() != null && node.getData().getCondition() != null
                        ? node.getData().getCondition()
                        : "true";

                if (webSocketHandler != null) {
                    webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "Вычисление условия");
                    webSocketHandler.sendLog(schemaId, "info", "Вычисление условия: " + conditionExpr, node.getId());
                }

                java.util.Map<String, Object> context = collectPredecessorResults(
                        schemaRepository.findById(schemaId), node.getId());
                boolean conditionResult = evaluateCondition(conditionExpr, context);
                result = String.valueOf(conditionResult);
                conditionResults.put(schemaId + ":" + node.getId(), result);

                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schemaId, "info",
                            "Условие '" + conditionExpr + "' = " + conditionResult, node.getId());
                }

            } else if ("transform".equals(node.getType())) {
                log.info("Transform node {} starting", node.getId());
                if (webSocketHandler != null) {
                    webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 30, "Применение трансформаций");
                }

                // Get input from predecessors
                var predResults = collectPredecessorResults(schemaRepository.findById(schemaId), node.getId());
                String input = null;
                if (!predResults.isEmpty()) {
                    Object firstValue = predResults.values().iterator().next();
                    input = firstValue != null ? firstValue.toString() : null;
                }
                log.info("Transform {} input: {}", node.getId(), input);

                // Apply transforms
                var transforms = node.getData() != null ? node.getData().getTransforms() : null;
                log.info("Transform {} transforms: {}", node.getId(), transforms);
                String transformed = transformService.applyTransforms(input, transforms);
                log.info("Transform {} transformed: {}", node.getId(), transformed);

                // Apply routes to determine which branch to take
                var routes = node.getData() != null ? node.getData().getRoutes() : null;
                log.info("Transform {} routes: {}", node.getId(), routes);
                String matchedPort = null;
                String routeResult = null;

                if (routes != null && !routes.isEmpty()) {
                    for (var route : routes) {
                        String evaluated = transformService.evaluateRoute(transformed, route);
                        log.info("Transform {} route {} evaluated: {}", node.getId(), route.getCondition(), evaluated);
                        if (evaluated != null) {
                            matchedPort = route.getCondition();
                            routeResult = evaluated;
                            break;
                        }
                    }
                }

                // If no route matched, use fallback
                if (matchedPort == null) {
                    String fallback = node.getData() != null ? node.getData().getFallbackValue() : null;
                    if (fallback != null && !fallback.isEmpty()) {
                        result = fallback;
                    } else {
                        result = transformed != null ? transformed : (input != null ? input : "");
                    }
                    matchedPort = "default";
                } else {
                    result = routeResult != null ? routeResult : transformed;
                }

                log.info("Transform {} result: {}, matchedPort: {}", node.getId(), result, matchedPort);

                // Store the matched port for edge routing
                conditionResults.put(schemaId + ":" + node.getId(), matchedPort);

                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schemaId, "info",
                            "Transform applied, route: " + matchedPort, node.getId());
                }

            } else if ("loop".equals(node.getType())) {
                String loopCond = node.getData() != null && node.getData().getLoopCondition() != null
                        ? node.getData().getLoopCondition()
                        : "iterations < 10";
                int maxIter = node.getData() != null ? node.getData().getMaxIterations() : 10;
                if (maxIter <= 0) maxIter = 10;

                int iterations = 0;
                boolean shouldContinue = true;

                while (shouldContinue && iterations < maxIter && !cancelFlag.get()) {
                    iterations++;
                    java.util.Map<String, Object> ctx = new java.util.HashMap<>();
                    ctx.put("iterations", iterations);
                    ctx.put("maxIterations", maxIter);
                    // Добавить результаты предшественников
                    ctx.putAll(collectPredecessorResults(schemaRepository.findById(schemaId), node.getId()));

                    if (webSocketHandler != null) {
                        int pct = (int) ((iterations / (double) maxIter) * 90);
                        webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", pct,
                                "Итерация " + iterations + "/" + maxIter);
                        webSocketHandler.sendLog(schemaId, "info",
                                "Итерация " + iterations + ": " + loopCond, node.getId());
                    }

                    shouldContinue = evaluateCondition(loopCond, ctx);
                    if (cancelFlag.get()) return;
                }

                result = "Завершено за " + iterations + " итераций";

            } else if ("memory".equals(node.getType())) {
                // Memory node: search MemPalace and return results
                String searchQuery = node.getData() != null && node.getData().getSourceData() != null
                        ? node.getData().getSourceData() : node.getName();
                if (webSocketHandler != null) {
                    webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "Поиск в памяти");
                    webSocketHandler.sendLog(schemaId, "info", "Поиск в памяти: " + searchQuery, node.getId());
                }
                if (memPalaceClient.isEnabled()) {
                    var memResults = memPalaceClient.search(searchQuery, null, null, 5);
                    if (memResults.isEmpty()) {
                        result = "Ничего не найдено по запросу: " + searchQuery;
                    } else {
                        StringBuilder sb = new StringBuilder();
                        for (var r : memResults) {
                            sb.append("- ").append(r.get("content")).append("\n");
                        }
                        result = sb.toString().trim();
                    }
                } else {
                    result = "Память недоступна (MemPalace не подключен)";
                }

            } else if ("guardrail".equals(node.getType())) {
                // Guardrail: validate/transform predecessor result
                var predResults = collectPredecessorResults(schemaRepository.findById(schemaId), node.getId());
                String input = predResults.values().stream().findFirst().map(Object::toString).orElse("");
                String rules = node.getData() != null && node.getData().getUserPrompt() != null
                        ? node.getData().getUserPrompt() : "";
                String guardrailMode = node.getData() != null && node.getData().getConfig() != null
                        ? (String) node.getData().getConfig().getOrDefault("mode", "validate") : "validate";

                if (webSocketHandler != null) {
                    webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "Валидация");
                    webSocketHandler.sendLog(schemaId, "info", "Guardrail mode=" + guardrailMode, node.getId());
                }

                if ("validate".equals(guardrailMode)) {
                    // Use LLM to validate against rules
                    String validationPrompt = "Проверь, соответствует ли следующий текст правилам.\n" +
                            "Правила:\n" + rules + "\n\nТекст:\n" + input +
                            "\n\nОтветь только 'ДА' или 'НЕТ: [причина]'";
                    result = llmService.chat(null, null, validationPrompt, null);
                } else if ("transform".equals(guardrailMode)) {
                    String transformPrompt = "Примени следующие правила трансформации к тексту.\n" +
                            "Правила:\n" + rules + "\n\nТекст:\n" + input;
                    result = llmService.chat(null, null, transformPrompt, null);
                } else {
                    result = input; // filter mode: pass through
                }

            } else if ("human".equals(node.getType())) {
                // Human node: wait for human approval (simulated — in real app would wait for WebSocket message)
                var predResults = collectPredecessorResults(schemaRepository.findById(schemaId), node.getId());
                String input = predResults.values().stream().findFirst().map(Object::toString).orElse("");
                String question = node.getData() != null && node.getData().getUserPrompt() != null
                        ? node.getData().getUserPrompt() : "Подтвердите результат";

                if (webSocketHandler != null) {
                    webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "Ожидание подтверждения");
                    webSocketHandler.sendLog(schemaId, "warning", "⏸ Требуется подтверждение: " + question, node.getId());
                }
                // Auto-approve after a short wait (in production this would wait for real human input)
                if (!sleepWithCancel(1000, cancelFlag)) return;
                result = "Подтверждено: " + input;
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schemaId, "success", "Авто-подтверждение", node.getId());
                }

            } else if ("fallback".equals(node.getType())) {
                // Fallback: execute only if predecessor failed
                WorkflowSchema fallbackSchema = schemaRepository.findById(schemaId);
                var predResults = collectPredecessorResults(fallbackSchema, node.getId());
                // Check if any predecessor failed
                boolean predecessorFailed = false;
                if (fallbackSchema != null && fallbackSchema.getNodes() != null) {
                    List<Node> fallbackNodes = fallbackSchema.getNodes();
                    List<Edge> fallbackEdges = fallbackSchema.getEdges();
                    if (fallbackEdges != null) {
                        for (Edge edge : fallbackEdges) {
                            if (node.getId().equals(edge.getTarget())) {
                                for (Node n : fallbackNodes) {
                                    if (n.getId().equals(edge.getSource()) && n.getStatus() == Node.NodeStatus.FAILED) {
                                        predecessorFailed = true;
                                    }
                                }
                            }
                        }
                    }
                }
                if (predecessorFailed) {
                    String fallbackPrompt = node.getData() != null && node.getData().getUserPrompt() != null
                            ? node.getData().getUserPrompt() : "Резервная обработка ошибки";
                    result = llmService.chat(null, null, fallbackPrompt, null);
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schemaId, "warning", "Fallback активирован: " + node.getName(), node.getId());
                    }
                } else {
                    result = "Пропущен (предшественник успешен)";
                }
            } else if ("subagent".equals(node.getType())) {
                result = executeSubagentNode(node, schemaId, cancelFlag, mode);
            } else if ("schemabuilder".equals(node.getType())) {
                result = executeSchemaBuilderNode(node, schemaId);
            }

            // 90%: Формирование результата
            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 90, "Формирование результата");
                webSocketHandler.sendLog(schemaId, "info", "Формирование результата", node.getId());
            }

            // Отправка результата
            if (webSocketHandler != null) {
                webSocketHandler.sendResult(schemaId, node.getId(), result);
            }

            // Store result in cache first (thread-safe), then on node data
            nodeResults.computeIfAbsent(schemaId, k -> new ConcurrentHashMap<>())
                    .put(node.getId(), result);
            if (node.getData() != null) {
                node.getData().setResult(result);
            }

            // 100%: Завершено
            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "COMPLETED", 100, "Завершено");
                webSocketHandler.sendLog(schemaId, "success", "Узел успешно выполнен", node.getId());
            }

            node.setStatus(Node.NodeStatus.COMPLETED);
            // Reset failure count on success
            Map<String, Integer> counts = nodeFailureCounts.get(schemaId);
            if (counts != null) counts.remove(node.getId());

        } catch (Exception e) {
            if (cancelFlag.get()) {
                node.setStatus(Node.NodeStatus.FAILED);
                return;
            }

            // Convergence monitoring: track failures per node
            String execKey = schemaId;
            nodeFailureCounts.computeIfAbsent(execKey, k -> new ConcurrentHashMap<>());
            Map<String, Integer> counts = nodeFailureCounts.get(execKey);
            int failCount = counts.merge(node.getId(), 1, Integer::sum);

            if (failCount >= MAX_NODE_RESTARTS) {
                node.setStatus(Node.NodeStatus.BLOCKED);
                String msg = String.format("Узел заблокирован после %d неудачных попыток: %s",
                        failCount, e.getMessage());
                if (webSocketHandler != null) {
                    webSocketHandler.sendNodeBlocked(schemaId, node.getId(), failCount, e.getMessage());
                    webSocketHandler.sendLog(schemaId, "warning",
                            "⛔ Зацикливание: узел '" + node.getName() + "' заблокирован (" + failCount + " попыток). Рекомендуется: пропустить узел или остановить выполнение.",
                            node.getId());
                }
                log.error("Узел заблокирован после {} неудачных попыток: {}", failCount, e.getMessage());
            } else {
                node.setStatus(Node.NodeStatus.FAILED);
                String retryMsg = String.format("Ошибка (%d/%d): %s", failCount, MAX_NODE_RESTARTS, e.getMessage());
                if (webSocketHandler != null) {
                    webSocketHandler.sendError(schemaId, node.getId(), retryMsg);
                    webSocketHandler.sendLog(schemaId, "error",
                            "⚠️ Попытка " + failCount + "/" + MAX_NODE_RESTARTS + ": " + e.getMessage(), node.getId());
                }
                log.error("Ошибка выполнения узла {} ({}/{}): {}", node.getId(), failCount, MAX_NODE_RESTARTS, e.getMessage());
            }
        }
    }

    private boolean sleepWithCancel(long millis, AtomicBoolean cancelFlag) {
        if (cancelFlag.get()) {
            return false;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !cancelFlag.get();
        }
        return !cancelFlag.get();
    }

    private String getNodeShape(String type) {
        return switch (type) {
            case "agent" -> "";
            case "source" -> "[/";
            case "output" -> "[\\";
            case "condition" -> "{";
            default -> "[";
        };
    }

    private String getEdgeStyle(String type) {
        return switch (type) {
            case "control" -> "==>";
            case "condition" -> "-.->";
            default -> "-->";
        };
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

    // Package-private accessors for testing
    List<List<Node>> getExecutionLevelsPublic(WorkflowSchema schema) { return getExecutionLevels(schema); }
    Set<String> computeSkippedNodesPublic(WorkflowSchema schema) { return computeSkippedNodes(schema); }
    String interpolateVariablesPublic(String text, WorkflowSchema schema, Map<String, Object> preds) { return interpolateVariables(text, schema, preds); }
    String buildContextBlockPublic(Map<String, Object> preds) { return buildContextBlock(preds); }
    boolean sleepWithCancelPublic(long millis, AtomicBoolean cancelFlag) { return sleepWithCancel(millis, cancelFlag); }
    void setConditionResult(String key, String value) { conditionResults.put(key, value); }
    void setId(String id) { /* for test setup */ }
    String writeOutputPublic(String outputType, String filePath, String fileFormat, String content) { return writeOutput(outputType, filePath, fileFormat, content); }

    private final Map<String, String> outputFileRegistry = new ConcurrentHashMap<>();

    public String getOutputFileContent(String schemaId, String nodeId) {
        String key = schemaId + ":" + nodeId;
        String filePath = outputFileRegistry.get(key);
        if (filePath == null) return null;
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(filePath));
        } catch (Exception e) {
            return null;
        }
    }

    private String writeOutput(String outputType, String filePath, String fileFormat, String content) {
        if (content == null || content.isBlank()) {
            return "Нет данных для вывода";
        }
        if ("file".equals(outputType) && filePath != null && !filePath.isBlank()) {
            try {
                java.nio.file.Path path = java.nio.file.Path.of(filePath);
                java.nio.file.Files.createDirectories(path.getParent());
                String dataToWrite = content;
                if ("json".equals(fileFormat)) {
                    dataToWrite = "{\n  \"result\": " + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(content) + ",\n  \"timestamp\": " + System.currentTimeMillis() + "\n}";
                }
                java.nio.file.Files.writeString(path, dataToWrite);
                return "Сохранено в файл: " + filePath;
            } catch (Exception e) {
                return "Ошибка записи файла: " + e.getMessage();
            }
        }
        // "log" or null — just return content
        return content;
    }

    private String simulateAgentNode(Node node, String schemaId) {
        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "DRY_RUN: Симуляция");
            webSocketHandler.sendLog(schemaId, "warning", "DRY_RUN: Симуляция LLM вызова (результат не сохраняется)", node.getId());
        }
        String prompt = node.getData() != null && node.getData().getUserPrompt() != null
                ? node.getData().getUserPrompt() : "Анализируй данные";
        String model = node.getData() != null ? node.getData().getModel() : "unknown";
        String simulatedResult = "[DRY_RUN] Симулированный ответ от " + model + "\nПромпт: " + prompt.substring(0, Math.min(100, prompt.length())) + "...";
        if (webSocketHandler != null) {
            webSocketHandler.sendResult(schemaId, node.getId(), simulatedResult);
        }
        return simulatedResult;
    }

    private String analyzeAgentNode(Node node, String schemaId) {
        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "ANALYZE: Блокировка мутаций");
            webSocketHandler.sendLog(schemaId, "warning", "ANALYZE: Заблокированы file/API операции", node.getId());
        }
        String prompt = node.getData() != null && node.getData().getUserPrompt() != null
                ? node.getData().getUserPrompt() : "Анализируй данные";
        String model = node.getData() != null ? node.getData().getModel() : "unknown";
        String analysisResult = "[ANALYZE] LLM вызов выполнен, мутации заблокированы\nМодель: " + model + "\nПромпт: " + prompt.substring(0, Math.min(100, prompt.length())) + "...";
        if (webSocketHandler != null) {
            webSocketHandler.sendResult(schemaId, node.getId(), analysisResult);
        }
        return analysisResult;
    }

private String executeAgentNode(Node node, String schemaId) {
        boolean useTools = node.getData() != null && node.getData().getEnabledTools() != null
                && !node.getData().getEnabledTools().isEmpty();

        if (useTools) {
            return executeToolAgentNode(node, schemaId);
        }

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "Отправка запроса к AI");
            webSocketHandler.sendLog(schemaId, "info", "Отправка запроса к LLM", node.getId());
        }

        String prompt = node.getData() != null && node.getData().getUserPrompt() != null
                ? node.getData().getUserPrompt()
                : "Анализируй данные";
        String systemPrompt = node.getData() != null ? node.getData().getSystemPrompt() : null;

        WorkflowSchema currentSchema = schemaRepository.findById(schemaId);
        String model = resolveModel(node.getData() != null ? node.getData().getModel() : null, currentSchema);
        Map<String, Object> predecessorResults = collectPredecessorResults(currentSchema, node.getId());
        String contextBlock = buildContextBlock(predecessorResults);
        if (!contextBlock.isEmpty()) {
            String effectiveSystem = (systemPrompt != null ? systemPrompt + "\n\n" : "") +
                    "Контекст от предыдущих узлов:\n" + contextBlock;
            systemPrompt = effectiveSystem;
        }

        prompt = interpolateVariables(prompt, currentSchema, predecessorResults);
        if (systemPrompt != null) {
            systemPrompt = interpolateVariables(systemPrompt, currentSchema, predecessorResults);
        }

        if (memPalaceClient.isEnabled()) {
            String memoryContext = memPalaceClient.buildGraphContext(prompt, 5);
            if (!memoryContext.isEmpty()) {
                systemPrompt = (systemPrompt != null ? systemPrompt + "\n\n" : "") + memoryContext;
            }
        }

        String result = llmService.streamingChat(model, systemPrompt, prompt, null,
                token -> {
                    if (webSocketHandler != null) {
                        webSocketHandler.sendToken(schemaId, node.getId(), token);
                    }
                });

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 70, "Ответ получен");
            webSocketHandler.sendLog(schemaId, "info", "Ответ от LLM получен", node.getId());
        }

        if (memPalaceClient.isEnabled() && result != null && !result.isBlank()) {
            memPalaceClient.addDrawer("axolotl", "agent-results",
                    node.getName() + ": " + result.substring(0, Math.min(500, result.length())),
                    "schema:" + schemaId);
        }

        return result;
    }

    private String executeToolAgentNode(Node node, String schemaId) {
        String agentType = node.getData().getAgentType();
        List<String> enabledTools = node.getData().getEnabledTools();
        int maxToolCalls = node.getData().getMaxToolCalls() > 0 ? node.getData().getMaxToolCalls() : 10;

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 10, "Инициализация агента с инструментами");
            webSocketHandler.sendLog(schemaId, "info", "��гент типа: " + agentType + ", инструменты: " + enabledTools, node.getId());
        }

        String prompt = node.getData().getUserPrompt();
        String systemPrompt = node.getData().getSystemPrompt();

        WorkflowSchema currentSchema = schemaRepository.findById(schemaId);
        String model = resolveModel(node.getData().getModel(), currentSchema);
        Map<String, Object> predecessorResults = collectPredecessorResults(currentSchema, node.getId());
        String contextBlock = buildContextBlock(predecessorResults);

        prompt = interpolateVariables(prompt, currentSchema, predecessorResults);
        if (systemPrompt != null) {
            systemPrompt = interpolateVariables(systemPrompt, currentSchema, predecessorResults);
        }

        String toolDefs = buildToolDefinitions(enabledTools);
        String toolInstructions = buildToolInstructions(enabledTools);
        systemPrompt = (systemPrompt != null ? systemPrompt + "\n\n" : "") + toolInstructions;

        if (!contextBlock.isEmpty()) {
            systemPrompt += "\n\nКонтекст от предыдущих узлов:\n" + contextBlock;
        }

        if (memPalaceClient.isEnabled()) {
            String memoryContext = memPalaceClient.buildGraphContext(prompt, 5);
            if (!memoryContext.isEmpty()) {
                systemPrompt += "\n\n" + memoryContext;
            }
        }

        List<Node.Message> messages = new ArrayList<>();
        messages.add(new Node.Message("system", systemPrompt));
        messages.add(new Node.Message("user", prompt));

        StringBuilder fullResponse = new StringBuilder();
        int toolCallCount = 0;
        int predictCallCount = 0;
        int iterationCount = 0;
        long totalStartTime = System.currentTimeMillis();
        String lastResponse = null;

        while (toolCallCount < maxToolCalls) {
            long iterationStartTime = System.currentTimeMillis();
            iterationCount++;

            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 20 + (toolCallCount * 5),
                        "Итерация " + iterationCount + " из " + maxToolCalls);
                webSocketHandler.sendLog(schemaId, "info", "Итерация " + iterationCount, node.getId());
            }

            lastResponse = llmService.chat(model, null, buildMessagesForToolCall(messages), null);
            messages.add(new Node.Message("assistant", lastResponse));
            fullResponse.append(lastResponse).append("\n");

            List<Map<String, Object>> toolCalls = parseToolCalls(lastResponse);
            if (toolCalls.isEmpty()) {
                long iterDuration = System.currentTimeMillis() - iterationStartTime;
                if (webSocketHandler != null) {
                    webSocketHandler.sendIteration(schemaId, node.getId(), iterationCount, iterDuration, 0, 0);
                }
                break;
            }

            int toolsInThisIteration = 0;
            for (Map<String, Object> toolCall : toolCalls) {
                if (toolCallCount >= maxToolCalls) {
                    sendUserApprovalRequest(schemaId, node.getId(), toolCallCount, maxToolCalls);
                    break;
                }

                String toolId = (String) toolCall.get("name");
                Map<String, Object> args = (Map<String, Object>) toolCall.get("arguments");

                String toolResult = executeToolCall(toolId, args, node, schemaId);
                messages.add(new Node.Message("tool", toolResult));
                messages.add(new Node.Message("tool_call_id", (String) toolCall.get("id")));

                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schemaId, "info", "Инструмент " + toolId + ": " +
                            (toolResult.length() > 100 ? toolResult.substring(0, 100) + "..." : toolResult), node.getId());
                }

                toolCallCount++;
                toolsInThisIteration++;
            }

            long iterDuration = System.currentTimeMillis() - iterationStartTime;
            if (webSocketHandler != null) {
                webSocketHandler.sendIteration(schemaId, node.getId(), iterationCount, iterDuration, toolsInThisIteration, 0);
            }

            if (toolCalls.isEmpty() || toolCallCount >= maxToolCalls) {
                break;
            }
        }

        long totalDuration = System.currentTimeMillis() - totalStartTime;
        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 90, "Завершение");
            webSocketHandler.sendLog(schemaId, "info", "Выполнено инструментов: " + toolCallCount, node.getId());
            webSocketHandler.sendTrajectoryComplete(schemaId, node.getId(), iterationCount, totalDuration, toolCallCount, predictCallCount, 0, 0.0);
        }

        if (memPalaceClient.isEnabled() && fullResponse.length() > 0) {
            String summary = fullResponse.substring(0, Math.min(500, fullResponse.length()));
            memPalaceClient.addDrawer("axolotl", "agent-results", node.getName() + ": " + summary, "schema:" + schemaId);
            memPalaceClient.addDrawer("axolotl", "trajectories",
                    "Итераций: " + iterationCount + ", инструментов: " + toolCallCount + ", время: " + totalDuration + "мс",
                    "schema:" + schemaId + ",node:" + node.getId());
        }

        return lastResponse != null ? lastResponse : fullResponse.toString();
    }

    private String buildToolDefinitions(List<String> toolIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("## available_tools\n\n");
        sb.append("namespace functions {\n\n");
        for (String toolId : toolIds) {
            Tool tool = toolExecutor.getTool(toolId);
            if (tool != null) {
                sb.append("// ").append(tool.getDescription()).append("\n");
                sb.append("type ").append(toolId).append(" = (_: {\n");
                sb.append(tool.getInputSchema().replace("\"", "'")).append("\n}) => any;\n\n");
            }
        }
        sb.append("}\n");
        return sb.toString();
    }

    private String buildToolInstructions(List<String> toolIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("You have access to tools. To use a tool, respond with a JSON object in your final answer.\n\n");
        sb.append("Available tools:\n");
        for (String toolId : toolIds) {
            Tool tool = toolExecutor.getTool(toolId);
            if (tool != null) {
                sb.append("- ").append(toolId).append(": ").append(tool.getDescription()).append("\n");
            }
        }
        sb.append("\nTo call a tool, include tool_calls in your response:\n");
        sb.append("```json\n");
        sb.append("{\"role\": \"assistant\", \"content\": \"...\", \"tool_calls\": [");
        sb.append("{\"id\": \"call_1\", \"name\": \"tool_name\", \"arguments\": {\"param\": \"value\"}}]");
        sb.append("}\n```\n");
        return sb.toString();
    }

    private String buildMessagesForToolCall(List<Node.Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Node.Message msg : messages) {
            sb.append("<message role=\"").append(msg.getRole()).append("\">\n");
            sb.append(msg.getContent()).append("\n</message>\n");
        }
        return sb.toString();
    }

    private List<Map<String, Object>> parseToolCalls(String response) {
        List<Map<String, Object>> calls = new ArrayList<>();
        if (response == null || !response.contains("tool_calls")) {
            return calls;
        }

        try {
            int start = response.indexOf("tool_calls");
            int braceStart = response.indexOf("[", start);
            int braceEnd = response.indexOf("]", braceStart);
            if (braceStart > 0 && braceEnd > 0) {
                String toolsJson = response.substring(braceStart, braceEnd + 1);
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> parsed = mapper.readValue(toolsJson, List.class);
                calls.addAll(parsed);
            }
        } catch (Exception e) {
            log.warn("Failed to parse tool calls: {}", e.getMessage());
        }
        return calls;
    }

    private String executeToolCall(String toolId, Map<String, Object> args, Node node, String schemaId) {
        ToolPermission permission = null;
        if (node.getData().getToolPermissions() != null) {
            for (ToolPermission tp : node.getData().getToolPermissions()) {
                // Handle null toolId - try matching by index or enabled tools
                if (tp.getToolId() != null && tp.getToolId().equals(toolId)) {
                    permission = tp;
                    break;
                }
            }
        }

        // Fallback: if no permission found but toolId is in enabledTools, create permission
        if (permission == null) {
            List<String> enabledTools = node.getData().getEnabledTools();
            if (enabledTools != null && enabledTools.contains(toolId)) {
                permission = new ToolPermission(toolId);
                permission.setEnabled(true);
            }
        }

        ToolResult result = toolExecutor.execute(toolId, args, permission, schemaId, node.getId());
        return result.isSuccess() ? result.getOutput() : "Error: " + result.getError();
    }

    private void sendUserApprovalRequest(String schemaId, String nodeId, int toolCallCount, int maxToolCalls) {
        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "warning",
                    "Достигнут лимит инструментов (" + toolCallCount + "/" + maxToolCalls + "). Требуется подтверждение для продолжения.",
                    nodeId);
        }
    }

    private String executeOutputNode(Node node, String schemaId, ExecutionMode mode) {
        if (mode == ExecutionMode.ANALYZE || mode == ExecutionMode.DRY_RUN) {
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "warning", "Блокировка: запись не выполняется в режиме " + mode, node.getId());
            }
            return "[SIMULATED] Output node - no file/memory operations";
        }

        var predResults = collectPredecessorResults(schemaRepository.findById(schemaId), node.getId());
        String input = predResults.values().stream().findFirst().map(Object::toString).orElse("");
        String outputType = node.getData() != null && node.getData().getConfig() != null
                ? (String) node.getData().getConfig().getOrDefault("outputType", "log") : "log";

        if ("memory".equals(outputType)) {
            String wing = node.getData() != null && node.getData().getConfig() != null
                    ? (String) node.getData().getConfig().getOrDefault("memoryWing", "axolotl") : "axolotl";
            String room = node.getData() != null && node.getData().getConfig() != null
                    ? (String) node.getData().getConfig().getOrDefault("memoryRoom", "agent-results") : "agent-results";
            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "Сохранение в память");
                webSocketHandler.sendLog(schemaId, "info", "Сохранение в память: " + wing + "/" + room, node.getId());
            }
            boolean ok = memPalaceClient.addDrawer(wing, room, input, "schema:" + schemaId);
            String result = ok ? "Сохранено в память: " + wing + "/" + room : "Ошибка сохранения в память (MemPalace не подключен)";
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, ok ? "success" : "error", result, node.getId());
            }
            return result;
        }

        String filePath = node.getData() != null && node.getData().getConfig() != null
                ? (String) node.getData().getConfig().getOrDefault("filePath", "") : "";
        String fileFormat = node.getData() != null && node.getData().getConfig() != null
                ? (String) node.getData().getConfig().getOrDefault("fileFormat", "text") : "text";
        String result = writeOutput(outputType, filePath, fileFormat, input);
        if ("file".equals(outputType) && filePath != null && !filePath.isBlank()) {
            outputFileRegistry.put(schemaId + ":" + node.getId(), filePath);
        }
        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "info", "Output: " + result, node.getId());
        }
        return result;
    }

    private static final java.util.Set<String> BLOCKED_COMMAND_PATTERNS = java.util.Set.of(
            "rm -rf /", "mkfs", "dd if=", ":(){ :|:&", "> /dev/sd", "format ", "del /f /s /q c:",
            "shutdown", "reboot", "init 0", "init 6", "halt", "poweroff");

    private String sanitizeCommand(String command) {
        String lower = command.toLowerCase().trim();
        for (String blocked : BLOCKED_COMMAND_PATTERNS) {
            if (lower.contains(blocked.toLowerCase())) {
                throw new SecurityException("Command blocked: contains dangerous pattern '" + blocked + "'");
            }
        }
        // Block shell expansions that can escape sandboxing
        if (lower.contains("$(rm ") || lower.contains("`rm ") || lower.contains("/dev/null >")) {
            throw new SecurityException("Command blocked: contains dangerous shell expansion");
        }
        return command;
    }

    private String executeCommandNode(Node node, String schemaId) {
        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 20, "Выполнение команды");
        }

        String command = node.getData() != null && node.getData().getConfig() != null
                ? (String) node.getData().getConfig().getOrDefault("command", "") : "";
        String workingDir = node.getData() != null && node.getData().getConfig() != null
                ? (String) node.getData().getConfig().getOrDefault("workingDir", "") : "";
        int timeout = node.getData() != null && node.getData().getConfig() != null
                ? (Integer) node.getData().getConfig().getOrDefault("timeout", 60) : 60;

        if (command == null || command.isBlank()) {
            return "Ошибка: команда не указана";
        }

        try {
            command = sanitizeCommand(command);
        } catch (SecurityException e) {
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "error", "Command blocked: " + e.getMessage(), node.getId());
            }
            return "Blocked: " + e.getMessage();
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            if (workingDir != null && !workingDir.isBlank()) {
                pb.directory(new java.io.File(workingDir));
            }
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try {
                boolean finished = process.waitFor(timeout, java.util.concurrent.TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return "Таймаут после " + timeout + " сек";
                }
                int exitCode = process.exitValue();
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                output = sb.toString();
                node.getData().setResult(output);
                node.getData().setConfig(java.util.Map.of("exitCode", exitCode));
                String result = output.isEmpty() ? "(пусто)" : output;
                if (exitCode == 0) {
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schemaId, "success", "Команда выполнена (exit " + exitCode + ")", node.getId());
                    }
                } else {
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schemaId, "error", "Команда завершена с ошибкой (exit " + exitCode + ")", node.getId());
                    }
                }
                return result.trim();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return "Прервано: " + e.getMessage();
            }
        } catch (Exception e) {
            log.error("Ошибка выполнения команды: {}", e.getMessage(), e);
            return "Ошибка: " + e.getMessage();
        }
    }

    @org.springframework.beans.factory.annotation.Value("${axolotl.sandbox.allowedWriteDirs:.}")
    private java.util.List<String> allowedWriteDirs;

    private boolean isPathAllowed(String filePath) {
        if (allowedWriteDirs == null || allowedWriteDirs.isEmpty()) return true; // no restrictions configured
        try {
            java.nio.file.Path resolved = java.nio.file.Path.of(filePath).toAbsolutePath().normalize();
            for (String dir : allowedWriteDirs) {
                java.nio.file.Path allowedBase = java.nio.file.Path.of(dir).toAbsolutePath().normalize();
                if (resolved.startsWith(allowedBase)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private String executeFileWriteNode(Node node, String schemaId) {
        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 20, "Запись в файл");
        }

        String filePath = node.getData() != null && node.getData().getConfig() != null
                ? (String) node.getData().getConfig().getOrDefault("filePath", "") : "";
        String writeMode = node.getData() != null && node.getData().getConfig() != null
                ? (String) node.getData().getConfig().getOrDefault("writeMode", "overwrite") : "overwrite";

        if (filePath == null || filePath.isBlank()) {
            return "Ошибка: путь к файлу не указан";
        }

        // Block path traversal
        String normalizedPath = java.nio.file.Path.of(filePath).toAbsolutePath().normalize().toString();
        if (filePath.contains("..") || !isPathAllowed(normalizedPath)) {
            String msg = "Access denied: path outside allowed directories — " + filePath;
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "error", msg, node.getId());
            }
            return msg;
        }

        var predResults = collectPredecessorResults(schemaRepository.findById(schemaId), node.getId());
        String content = predResults.values().stream().findFirst().map(Object::toString).orElse("");

        try {
            java.io.File file = new java.io.File(filePath);
            if ("create-dir".equals(writeMode)) {
                java.io.File dir = file.getParentFile();
                if (dir != null && !dir.exists()) {
                    dir.mkdirs();
                }
            }
            java.io.FileWriter writer = new java.io.FileWriter(file, "append".equals(writeMode));
            writer.write(content);
            writer.close();

            String result = "Записано в файл: " + filePath;
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "success", result, node.getId());
            }
            return result;
        } catch (Exception e) {
            log.error("Ошибка записи в файл: {}", e.getMessage(), e);
            return "Ошибка записи: " + e.getMessage();
        }
    }

    private static final int MAX_SUBAGENT_DEPTH = 5;

    private void validateUrl(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
                throw new SecurityException("Only http/https URLs allowed");
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                throw new SecurityException("URL must have a valid host");
            }
            // Block internal/private network addresses
            java.net.InetAddress address = java.net.InetAddress.getByName(host);
            if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
                throw new SecurityException("Access to internal network addresses is blocked");
            }
        } catch (java.net.UnknownHostException e) {
            throw new SecurityException("Cannot resolve host: " + e.getMessage());
        }
    }

    private String fetchUrlContent(String url) {
        try {
            validateUrl(url);
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(30))
                    .build();
            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String content = response.body();
                return content.length() > 50000 ? content.substring(0, 50000) : content;
            }
            return "Ошибка загрузки URL: HTTP " + response.statusCode();
        } catch (Exception e) {
            return "Ошибка загрузки URL: " + e.getMessage();
        }
    }

    private String readProjectContext(String projectPath, Map<String, Object> config) {
        try {
            java.nio.file.Path root = java.nio.file.Path.of(projectPath);
            if (!java.nio.file.Files.exists(root)) {
                return "Ошибка: путь не существует: " + projectPath;
            }

            int maxDepth = config != null && config.get("maxDepth") != null
                    ? ((Number) config.get("maxDepth")).intValue() : 4;
            int maxFiles = config != null && config.get("maxFiles") != null
                    ? ((Number) config.get("maxFiles")).intValue() : 50;

            Set<String> excludeDirs = Set.of(".git", "node_modules", ".idea", "target", "dist", "__pycache__", ".next", "build");
            Set<String> includeExtensions = config != null && config.get("includeExtensions") != null
                    ? new HashSet<>((List<String>) config.get("includeExtensions"))
                    : Set.of(".java", ".ts", ".tsx", ".vue", ".js", ".py", ".go", ".rs", ".yaml", ".yml", ".json", ".md", ".toml", ".xml", ".properties", ".sql", ".html", ".css");

            StringBuilder sb = new StringBuilder();
            sb.append("Project: ").append(root.getFileName()).append("\n");
            sb.append("Path: ").append(projectPath).append("\n\n");

            // File tree
            sb.append("=== FILE TREE ===\n");
            List<String> files = new ArrayList<>();
            java.nio.file.Files.walk(root, maxDepth)
                    .filter(p -> {
                        for (int i = 0; i < p.getNameCount(); i++) {
                            if (excludeDirs.contains(p.getName(i).toString())) return false;
                        }
                        return true;
                    })
                    .filter(java.nio.file.Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        int dot = name.lastIndexOf('.');
                        return dot >= 0 && includeExtensions.contains(name.substring(dot));
                    })
                    .limit(maxFiles)
                    .forEach(p -> files.add(root.relativize(p).toString()));

            for (String f : files) {
                sb.append("  ").append(f).append("\n");
            }
            sb.append("\n");

            // Key files content
            sb.append("=== KEY FILES ===\n");
            List<String> priorityFiles = List.of("README.md", "CLAUDE.md", "package.json", "pom.xml", "Cargo.toml", "go.mod", "pyproject.toml");
            for (String pf : priorityFiles) {
                java.nio.file.Path p = root.resolve(pf);
                if (java.nio.file.Files.exists(p)) {
                    String content = java.nio.file.Files.readString(p);
                    sb.append("\n--- ").append(pf).append(" ---\n");
                    sb.append(content.length() > 3000 ? content.substring(0, 3000) + "\n... (truncated)" : content);
                    sb.append("\n");
                }
            }

            // Source files content (limited)
            boolean includeSources = config == null || !Boolean.FALSE.equals(config.get("includeSources"));
            if (includeSources) {
                sb.append("\n=== SOURCE FILES ===\n");
                int fileCount = 0;
                for (String f : files) {
                    if (fileCount >= 20) break;
                    java.nio.file.Path p = root.resolve(f);
                    try {
                        String content = java.nio.file.Files.readString(p);
                        if (content.length() > 2000) {
                            content = content.substring(0, 2000) + "\n... (truncated)";
                        }
                        sb.append("\n--- ").append(f).append(" ---\n");
                        sb.append(content);
                        sb.append("\n");
                        fileCount++;
                    } catch (Exception ignored) {}
                }
            }

            String result = sb.toString();
            return result.length() > 100000 ? result.substring(0, 100000) + "\n... (truncated)" : result;
        } catch (Exception e) {
            return "Ошибка чтения проекта: " + e.getMessage();
        }
    }

    private String executeSubagentNode(Node node, String schemaId, AtomicBoolean cancelFlag, ExecutionMode mode) {
        String targetSchemaId = node.getData() != null ? node.getData().getSubagentSchemaId() : null;
        if (targetSchemaId == null || targetSchemaId.isBlank()) {
            return "Ошибка: Subagent не указывает на схему";
        }

        WorkflowSchema targetSchema = schemaRepository.findById(targetSchemaId);
        if (targetSchema == null) {
            return "Ошибка: Схема не найдена: " + targetSchemaId;
        }

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 20, "Запуск подсхемы: " + targetSchema.getName());
            webSocketHandler.sendLog(schemaId, "info", "→ Subagent: запуск " + targetSchema.getName(), node.getId());
        }

        StringBuilder nestedResult = new StringBuilder();
        nestedResult.append("=== Subagent: ").append(targetSchema.getName()).append(" ===\n");

        Map<String, Object> predecessorResults = collectPredecessorResults(schemaRepository.findById(schemaId), node.getId());
        Map<String, String> inputMapping = node.getData() != null ? node.getData().getInputMapping() : null;

        if (predecessorResults.isEmpty() && webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "info", "  (без входных данных)", node.getId());
        }

        nestedResult.append("Входные данные: ").append(predecessorResults.values().stream().findFirst().map(Object::toString).orElse("(нет)")).append("\n");

        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "info", "  Результат: " + nestedResult, node.getId());
        }

        nestedResult.append("=== Subagent завершён ===");
        return nestedResult.toString();
    }

    private static final String SCHEMA_BUILDER_SYSTEM_PROMPT = """
            You are a workflow architect. Given an analysis/result text, design an Axolotl workflow schema.
            
            CRITICAL PATH RULES:
            - If input mentions "backend-next" or "frontend-next" or "-next", you MUST use these exact paths:
              * /backend-next/ NOT /backend/
              * /Users/evgenijtihomirov/git/Axolotl/Axolotl/backend-next/ NOT /Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/
              * Use FULL paths starting with /Users/evgenijtihomirov/git/Axolotl/Axolotl/...
            
            For generated schemas, ALWAYS set model to "minimax-max" (powerful) or "minimax-m2.5-free" (simple).
            Avoid using openai models unless explicitly requested.
            
            Respond ONLY with valid JSON, no markdown fences:
            {
              "name": "Schema name",
              "description": "What this workflow does",
              "nodes": [
                {
                  "id": "n1",
                  "type": "source|agent|output|condition|loop|memory|guardrail|human|fallback|schemabuilder",
                  "name": "Node name",
                  "position": {"x": 100, "y": 200},
                  "data": {
                    "userPrompt": "...",
                    "systemPrompt": "...",
                    "model": "minimax-max",
                    "agentType": "coder|assistant|researcher|reviewer",
                    "enabledTools": ["file_read", "file_write", "directory_read", "grep", "bash"],
                    "maxToolCalls": 50,
                    "toolPermissions": [
                      {"toolId": "file_read", "allowedPaths": ["/full/path/**"], "enabled": true}
                    ]
                  }
                }
              ],
              "edges": [
                {"source": "n1", "target": "n2"}
              ],
              "planExplanation": "Markdown text explaining the plan"
            }
            Rules:
            - Use agent nodes with detailed userPrompt and systemPrompt
            - For tool-enabled agents, ALWAYS specify enabledTools array and toolPermissions
            - Tools: file_read, file_write, directory_read, grep, git, bash, memory_read, memory_write, memory_search, web_search, web_fetch
            - Typical flow for implementation: source -> agent (read files) -> agent (apply changes) -> agent (test)
            - Position nodes with reasonable spacing (x increments of 300)
            - Each node needs a unique id (n1, n2, n3...)
            """;

    public static final String ARCHITECT_ANALYST_PROMPT = """
            You are a senior software architect analyzing a project's codebase structure.
            Given the project's file tree, key configuration files, and source code excerpts, provide:

            1. **Architecture Overview**: What patterns and frameworks are used? How is the code organized?
            2. **Technology Stack**: Languages, frameworks, build tools, databases.
            3. **Module Breakdown**: List each major module/directory and its responsibility.
            4. **Extension Points**: Where can new features be plugged in? What interfaces/hooks exist?
            5. **Constraints**: Any architectural decisions, conventions, or limitations to be aware of.

            Be specific — reference actual file paths and class names. Output in structured markdown.
            """;

    public static final String FEATURE_DESIGNER_PROMPT = """
            You are a feature design architect. Given:
            - A project's architecture analysis
            - A list of requested features/improvements

            For each feature, design:
            1. **Scope**: Which files/modules need changes
            2. **Approach**: Step-by-step implementation plan
            3. **Dependencies**: What must be built first
            4. **Risks**: Potential breaking changes or conflicts
            5. **Estimate**: Relative complexity (S/M/L/XL)

            Output structured markdown with clear sections per feature.
            Prioritize features by dependency order.
            """;

    public static final String TASK_BREAKDOWN_PROMPT = """
            You are a project planner breaking down feature designs into actionable development tasks.

            Given feature designs and architecture context, output a structured task list as JSON:
            {
              "tasks": [
                {
                  "title": "Short task title",
                  "description": "What to do, which files to change",
                  "priority": "HIGH|MEDIUM|LOW",
                  "dependencies": ["task title that must be done first"],
                  "acceptanceCriteria": ["testable criterion 1", "criterion 2"],
                  "order": 1
                }
              ]
            }

            Rules:
            - Each task should be completable in 1-4 hours
            - Include specific file paths and function names
            - Every task must have at least 2 acceptance criteria
            - Order tasks by dependency chain
            - Group related tasks together
            """;

    public static final String PLANNING_WORKFLOW_USER_PROMPT = """
            Analyze this project and design a plan for implementing the following features:

            {{features}}

            Use the project context provided by the previous node to create a detailed implementation plan.
            """;

    private String executeSchemaBuilderNode(Node node, String schemaId) {
        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 20, "Building schema from agent result");
            webSocketHandler.sendLog(schemaId, "info", "🏗️ SchemaBuilder: generating workflow", node.getId());
        }

        var predResults = collectPredecessorResults(schemaRepository.findById(schemaId), node.getId());
        String input = predResults.values().stream().findFirst().map(Object::toString).orElse("");
        if (input.isBlank()) {
            return "Error: no predecessor result to build schema from";
        }

        boolean generateMd = node.getData() != null && node.getData().getConfig() != null
                && Boolean.TRUE.equals(node.getData().getConfig().get("generateMd"));

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 40, "Sending to LLM");
        }

        String model = resolveModel(node.getData() != null ? node.getData().getModel() : null, schemaRepository.findById(schemaId));
        String llmResponse = llmService.chat(model, SCHEMA_BUILDER_SYSTEM_PROMPT, input, null);

        if (llmResponse == null || llmResponse.isBlank()
                || llmResponse.startsWith("Error:") || llmResponse.startsWith("Ollama")) {
            return "Error: LLM call failed — " + (llmResponse != null ? llmResponse : "empty response");
        }

        // Strip markdown fences if present
        String jsonStr = llmResponse.trim();
        if (jsonStr.startsWith("```")) {
            jsonStr = jsonStr.replaceFirst("^```\\w*\\n?", "").replaceFirst("\\n?```$", "");
        }

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 60, "Parsing schema");
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root;
        try {
            root = mapper.readTree(jsonStr);
        } catch (Exception e) {
            return "Error: failed to parse LLM response as JSON: " + e.getMessage();
        }

        // Build WorkflowSchema from parsed JSON
        WorkflowSchema newSchema = new WorkflowSchema();
        newSchema.setName(root.has("name") ? root.get("name").asText() : "Generated Schema");
        newSchema.setDescription(root.has("description") ? root.get("description").asText() : "");
        newSchema.setVersion("1.0");

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
                    if (d.has("enabledTools") && d.get("enabledTools").isArray()) {
                        List<String> tools = new ArrayList<>();
                        for (JsonNode t : d.get("enabledTools")) tools.add(t.asText());
                        data.setEnabledTools(tools);
                    }
                    if (d.has("maxToolCalls")) data.setMaxToolCalls(d.get("maxToolCalls").asInt());
                    // Note: config parsing skipped - complex nested objects handled separately if needed
                    // Parse toolPermissions
                    if (d.has("toolPermissions") && d.get("toolPermissions").isArray()) {
                        List<ToolPermission> tps = new ArrayList<>();
                        for (JsonNode tpNode : d.get("toolPermissions")) {
                            ToolPermission tp = new ToolPermission();
                            if (tpNode.has("toolId")) tp.setToolId(tpNode.get("toolId").asText());
                            if (tpNode.has("enabled")) tp.setEnabled(tpNode.get("enabled").asBoolean());
                            if (tpNode.has("allowedPaths") && tpNode.get("allowedPaths").isArray()) {
                                Set<String> paths = new java.util.HashSet<>();
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
        newSchema.setNodes(nodes);

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
        newSchema.setEdges(edges);

        // Save the new schema
        WorkflowSchema saved = createSchema(newSchema);

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 75, "Creating plan tasks");
        }

        // Create a SUBSCHEMA plan linked to the generated schema
        try {
            Plan parentPlan = planService.getPlan("default");
            Plan subPlan = planService.importSchemaAsSubPlan("default", parentPlan.getId(), saved.getId());
            subPlan.setName(saved.getName());
            planService.updatePlan(subPlan);

            // Try to extract structured tasks from predecessor (Task Breakdown JSON)
            boolean tasksFromJson = false;
            for (var predVal : predResults.values()) {
                String predStr = predVal.toString();
                if (predStr.contains("\"tasks\"") && predStr.contains("\"title\"")) {
                    try {
                        JsonNode taskRoot = mapper.readTree(predStr);
                        if (taskRoot.has("tasks") && taskRoot.get("tasks").isArray()) {
                            for (JsonNode taskNode : taskRoot.get("tasks")) {
                                String title = taskNode.has("title") ? taskNode.get("title").asText() : "Task";
                                String desc = taskNode.has("description") ? taskNode.get("description").asText() : "";
                                String prio = taskNode.has("priority") ? taskNode.get("priority").asText() : "MEDIUM";
                                List<String> deps = new ArrayList<>();
                                if (taskNode.has("dependencies") && taskNode.get("dependencies").isArray()) {
                                    for (JsonNode dep : taskNode.get("dependencies")) deps.add(dep.asText());
                                }
                                List<String> criteria = new ArrayList<>();
                                if (taskNode.has("acceptanceCriteria") && taskNode.get("acceptanceCriteria").isArray()) {
                                    for (JsonNode ac : taskNode.get("acceptanceCriteria")) criteria.add(ac.asText());
                                }
                                planService.addTask("default", title, desc, Priority.valueOf(prio.toUpperCase()), deps, null, null);
                            }
                            tasksFromJson = true;
                        }
                    } catch (Exception ignored) {}
                }
            }

            // Fallback: create plan tasks for each generated node
            if (!tasksFromJson) {
                for (Node schemaNode : nodes) {
                    try {
                        planService.addTask("default", schemaNode.getName(),
                                "Node in generated schema: " + saved.getName(),
                                Priority.MEDIUM, null, null, null);
                    } catch (Exception e) {
                        log.warn("Failed to create plan task for node {}: {}", schemaNode.getName(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to create subplan for generated schema: {}", e.getMessage());
        }

        // Optionally write .md explanation
        String planExplanation = root.has("planExplanation") ? root.get("planExplanation").asText() : "";
        if (generateMd && !planExplanation.isBlank()) {
            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 85, "Writing plan .md");
            }
            try {
                String mdContent = "# " + saved.getName() + "\n\n"
                        + saved.getDescription() + "\n\n"
                        + planExplanation + "\n\n"
                        + "## Nodes\n\n";
                for (Node n : nodes) {
                    mdContent += "- **" + n.getName() + "** (" + n.getType() + ")\n";
                }
                String mdPath = "plan_" + saved.getId().substring(0, 8) + ".md";
                writeOutput("file", mdPath, "markdown", mdContent);
            } catch (Exception e) {
                log.warn("Failed to write plan .md: {}", e.getMessage());
            }
        }

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 95, "Schema created");
            webSocketHandler.sendLog(schemaId, "success",
                    "🏗️ SchemaBuilder: created '" + saved.getName() + "' (" + nodes.size() + " nodes)", node.getId());
        }

        return "Schema created: " + saved.getName() + " (ID: " + saved.getId() + ", " + nodes.size() + " nodes, " + edges.size() + " edges)";
    }

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
        if (nodeModel != null && !nodeModel.isBlank()) return nodeModel;
        if (schema.getDefaultModel() != null && !schema.getDefaultModel().isBlank()) return schema.getDefaultModel();
        if (schema.getUserId() != null) {
            String userModel = settingsService.getUserDefaultModel(schema.getUserId());
            if (userModel != null && !userModel.isBlank()) return userModel;
        }
        String global = settingsService.getGlobalDefaultModel();
        if (global != null && !global.isBlank()) return global;
        return null;
    }
}
