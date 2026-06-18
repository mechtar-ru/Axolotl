package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.ExecutionMode;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared utility service for the execution engine.
 * Contains general-purpose helper methods extracted from NodeExecutor
 * to break circular setNodeExecutor dependencies.
 */
@Service
public class ExecutionUtilityService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionUtilityService.class);

    private final LlmService llmService;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final MemPalaceClient memPalaceClient;
    private final Neo4jSchemaRepository schemaRepository;
    private final ProjectContextBuilder projectContextBuilder;
    private final ExecutionStateManager stateManager;
    private final NodeSourceHandler nodeSourceHandler;
    private final NodeCommandExecutor nodeCommandExecutor;
    private final NodeFileWriter nodeFileWriter;

    @Value("${axolotl.sandbox.allowedWriteDirs:.}")
    private java.util.List<String> allowedWriteDirs;

    private static final int MAX_CONTEXT_CHARS = 4000;


    public ExecutionUtilityService(LlmService llmService,
                                    ExecutionWebSocketHandler webSocketHandler,
                                    MemPalaceClient memPalaceClient,
                                    Neo4jSchemaRepository schemaRepository,
                                    ProjectContextBuilder projectContextBuilder,
                                    ExecutionStateManager stateManager,
                                    NodeSourceHandler nodeSourceHandler,
                                    NodeCommandExecutor nodeCommandExecutor,
                                    NodeFileWriter nodeFileWriter) {
        this.llmService = llmService;
        this.webSocketHandler = webSocketHandler;
        this.memPalaceClient = memPalaceClient;
        this.schemaRepository = schemaRepository;
        this.projectContextBuilder = projectContextBuilder;
        this.stateManager = stateManager;
        this.nodeSourceHandler = nodeSourceHandler;
        this.nodeCommandExecutor = nodeCommandExecutor;
        this.nodeFileWriter = nodeFileWriter;
    }

    // ────────────────────────── model resolution ──────────────────────────

    /**
     * Simplified model resolution — takes pre-fetched values.
     * Falls back: nodeModel -> schemaModel -> userId -> globalModel -> null.
     */
    public String resolveModel(String nodeModel, String schemaModel, String userId, String globalModel) {
        if (nodeModel != null && !nodeModel.isBlank()) return nodeModel;
        if (schemaModel != null && !schemaModel.isBlank()) return schemaModel;
        if (globalModel != null && !globalModel.isBlank()) return globalModel;
        return null;
    }

    // ────────────────────────── predecessor results ──────────────────────────

    public Map<String, Object> collectPredecessorResults(WorkflowSchema schema, String nodeId) {
        Map<String, Object> results = new HashMap<>();
        if (schema == null || schema.getEdges() == null || schema.getNodes() == null) {
            return results;
        }
        Map<String, String> cached = stateManager.getNodeResults().getOrDefault(schema.getId(), Map.of());

        for (Edge edge : schema.getEdges()) {
            if (nodeId.equals(edge.getTarget())) {
                String sourceId = edge.getSource();
                String result = cached.get(sourceId);
                if (result == null) {
                    for (Node n : schema.getNodes()) {
                        if (sourceId.equals(n.getId()) && n.getData() != null && n.getData().getResult() != null) {
                            result = n.getData().getResult();
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

    // ────────────────────────── condition evaluation ──────────────────────────

    public boolean evaluateCondition(String expression, Map<String, Object> context) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        // Use executor-based timeout since GraalVM JS doesn't support js.timeout option on this version
        java.util.concurrent.ExecutorService exec = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<Boolean> future = exec.submit(() -> {
                try (Context ctx = Context.newBuilder("js")
                        .allowIO(false)
                        .allowCreateProcess(false)
                        .allowHostAccess(HostAccess.NONE)
                        .build()) {
                    org.graalvm.polyglot.Value bindings = ctx.getBindings("js");
                    context.forEach(bindings::putMember);
                    org.graalvm.polyglot.Value result = ctx.eval("js", "Boolean(" + expression + ")");
                    return result.asBoolean();
                }
            });
            return future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("JS condition evaluation timed out after 5s: {}", expression);
            return false;
        } catch (Exception e) {
            log.error("Failed to evaluate condition '{}': {}", expression, e.getMessage(), e);
            return false;
        } finally {
            exec.shutdownNow();
        }
    }

    // ────────────────────────── variable interpolation ──────────────────────────

    public String interpolateVariables(String text, WorkflowSchema schema, Map<String, Object> predecessorResults) {
        if (text == null || !text.contains("{{")) return text;

        String input = predecessorResults.values().stream().findFirst().map(Object::toString).orElse("");
        text = text.replace("{{input}}", input);

        String prevResult = predecessorResults.values().stream()
                .reduce((first, second) -> second).map(Object::toString).orElse("");
        text = text.replace("{{prev_result}}", prevResult);

        // {{sourceData}} — resolve from the first source-type node's sourceData field
        if (text.contains("{{sourceData}}")) {
            String sourceData = resolveSourceData(schema);
            text = text.replace("{{sourceData}}", sourceData != null ? sourceData : "");
        }

        if (text.contains("{{node:")) {
            if (schema.getNodes() != null) {
                for (Node n : schema.getNodes()) {
                    String result = n.getData() != null && n.getData().getResult() != null ? n.getData().getResult() : "";
                    String key = "{{node:" + n.getName() + "}}";
                    text = text.replace(key, result);
                }
            }
        }

        text = text.replace("{{schema_name}}", schema.getName() != null ? schema.getName() : "");

        return text;
    }

    private String resolveSourceData(WorkflowSchema schema) {
        return nodeSourceHandler.resolveSourceData(schema);
    }

    // ────────────────────────── context building ──────────────────────────

    public String buildContextBlock(Map<String, Object> predecessorResults) {
        if (predecessorResults.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        predecessorResults.forEach((name, value) -> {
            sb.append("[").append(name).append("]: ").append(value).append("\n");
        });
        String context = sb.toString().trim();

        if (context.length() > MAX_CONTEXT_CHARS) {
            log.info("Сжатие контекста: {} символов → обрезка", context.length());
            return context.substring(0, MAX_CONTEXT_CHARS) + "\n... [контекст обрезан]";
        }

        return context;
    }

    // ────────────────────────── sleep with cancel ──────────────────────────

    /**
     * Sleep with cancel check. Runs on virtual thread (via VirtualThreadPerTaskExecutor), so Thread.sleep is safe.
     */
    public boolean sleepWithCancel(long millis, AtomicBoolean cancelFlag) {
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

    // ────────────────────────── command node ──────────────────────────

    public String executeCommandNode(Node node, String schemaId) {
        return nodeCommandExecutor.execute(node, schemaId);
    }

    // ────────────────────────── file write node ──────────────────────────

    public String executeFileWriteNode(Node node, String schemaId) {
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

        String normalizedPath = Path.of(filePath).toAbsolutePath().normalize().toString();
            if (filePath.contains("..") || !nodeFileWriter.isPathAllowed(normalizedPath)) {
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
            try (java.io.FileWriter writer = new java.io.FileWriter(file, "append".equals(writeMode))) {
                writer.write(content);
            }

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

    // ────────────────────────── subagent node ──────────────────────────

    public String executeSubagentNode(Node node, String schemaId, AtomicBoolean cancelFlag, ExecutionMode mode) {
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

    // ────────────────────────── source node handling ──────────────────────────

    public String handleSourceNode(Node node, String schemaId) {
        return nodeSourceHandler.handleSourceNode(node, schemaId);
    }

}
