package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.ExecutionMode;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.Plan;
import com.agent.orchestrator.model.Priority;
import com.agent.orchestrator.model.Tool;
import com.agent.orchestrator.model.ToolPermission;
import com.agent.orchestrator.model.Tool.ToolResult;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.graalvm.polyglot.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(NodeExecutor.class);

    private final LlmService llmService;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final MemPalaceClient memPalaceClient;
    private final ToolExecutor toolExecutor;
    private final TransformService transformService;
    private final Neo4jSchemaRepository schemaRepository;
    private final PlanService planService;
    private final ProjectContextBuilder projectContextBuilder;

    @Value("${axolotl.sandbox.allowedWriteDirs:.}")
    private java.util.List<String> allowedWriteDirs;

    private final Map<String, Map<String, String>> nodeResults = new ConcurrentHashMap<>();
    private final Map<String, String> conditionResults = new ConcurrentHashMap<>();
    private final Map<String, String> outputFileRegistry = new ConcurrentHashMap<>();
    private final Map<String, Object> generatedFilesRegistry = new ConcurrentHashMap<>();

    private static final int MAX_CONTEXT_CHARS = 4000;
    private static final int MAX_SUBAGENT_DEPTH = 5;

    public NodeExecutor(LlmService llmService,
                        ExecutionWebSocketHandler webSocketHandler,
                        MemPalaceClient memPalaceClient,
                        ToolExecutor toolExecutor,
                        TransformService transformService,
                        Neo4jSchemaRepository schemaRepository,
                        PlanService planService,
                        ProjectContextBuilder projectContextBuilder) {
        this.llmService = llmService;
        this.webSocketHandler = webSocketHandler;
        this.memPalaceClient = memPalaceClient;
        this.toolExecutor = toolExecutor;
        this.transformService = transformService;
        this.schemaRepository = schemaRepository;
        this.planService = planService;
        this.projectContextBuilder = projectContextBuilder;
    }

    @PostConstruct
    void init() {
        toolExecutor.setWebSocketHandler(webSocketHandler);
        toolExecutor.setLlmService(llmService);
    }

    // ────────────────────────── result maps ──────────────────────────

    public Map<String, Map<String, String>> getNodeResults() {
        return nodeResults;
    }

    public Map<String, String> getConditionResults() {
        return conditionResults;
    }

    public Map<String, String> getOutputFileRegistry() {
        return outputFileRegistry;
    }

    public Map<String, Object> getGeneratedFilesRegistry() {
        return generatedFilesRegistry;
    }

    // ────────────────────────── main dispatcher ──────────────────────────

    public void executeNode(Node node, String schemaId, AtomicBoolean cancelFlag,
                            ExecutionMode mode, String resolvedModel) {
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
                    result = executeAgentNode(node, schemaId, resolvedModel);
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

                Map<String, Object> context = collectPredecessorResults(
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

                var predResults = collectPredecessorResults(schemaRepository.findById(schemaId), node.getId());
                String input = null;
                if (!predResults.isEmpty()) {
                    Object firstValue = predResults.values().iterator().next();
                    input = firstValue != null ? firstValue.toString() : null;
                }
                log.info("Transform {} input: {}", node.getId(), input);

                var transforms = node.getData() != null ? node.getData().getTransforms() : null;
                log.info("Transform {} transforms: {}", node.getId(), transforms);
                String transformed = transformService.applyTransforms(input, transforms);
                log.info("Transform {} transformed: {}", node.getId(), transformed);

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
                    Map<String, Object> ctx = new HashMap<>();
                    ctx.put("iterations", iterations);
                    ctx.put("maxIterations", maxIter);
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
                    String validationPrompt = "Проверь, соответствует ли следующий текст правилам.\n" +
                            "Правила:\n" + rules + "\n\nТекст:\n" + input +
                            "\n\nОтветь только 'ДА' или 'НЕТ: [причина]'";
                    result = llmService.chat(null, null, validationPrompt, null);
                } else if ("transform".equals(guardrailMode)) {
                    String transformPrompt = "Примени следующие правила трансформации к тексту.\n" +
                            "Правила:\n" + rules + "\n\nТекст:\n" + input;
                    result = llmService.chat(null, null, transformPrompt, null);
                } else {
                    result = input;
                }

            } else if ("verifier".equals(node.getType())) {
                result = executeVerifierNode(node, schemaId, resolvedModel);

            } else if ("review".equals(node.getType())) {
                result = executeReviewNode(node, schemaId, resolvedModel);

            } else if ("human".equals(node.getType())) {
                var predResults = collectPredecessorResults(schemaRepository.findById(schemaId), node.getId());
                String input = predResults.values().stream().findFirst().map(Object::toString).orElse("");
                String question = node.getData() != null && node.getData().getUserPrompt() != null
                        ? node.getData().getUserPrompt() : "Подтвердите результат";

                if (webSocketHandler != null) {
                    webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "Ожидание подтверждения");
                    webSocketHandler.sendLog(schemaId, "warning", "Требуется подтверждение: " + question, node.getId());
                }
                if (!sleepWithCancel(1000, cancelFlag)) return;
                result = "Подтверждено: " + input;
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schemaId, "success", "Авто-подтверждение", node.getId());
                }

            } else if ("fallback".equals(node.getType())) {
                WorkflowSchema fallbackSchema = schemaRepository.findById(schemaId);
                var predResults = collectPredecessorResults(fallbackSchema, node.getId());
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
                result = executeSchemaBuilderNode(node, schemaId, resolvedModel);
            }

            // 90%: Формирование результата
            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 90, "Формирование результата");
                webSocketHandler.sendLog(schemaId, "info", "Формирование результата", node.getId());
            }

            if (webSocketHandler != null) {
                webSocketHandler.sendResult(schemaId, node.getId(), result);
            }

            nodeResults.computeIfAbsent(schemaId, k -> new ConcurrentHashMap<>())
                    .put(node.getId(), result);
            if (node.getData() != null) {
                node.getData().setResult(result);
            }

            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "COMPLETED", 100, "Завершено");
                webSocketHandler.sendLog(schemaId, "success", "Узел успешно выполнен", node.getId());
            }

            node.setStatus(Node.NodeStatus.COMPLETED);

        } catch (Exception e) {
            if (cancelFlag.get()) {
                node.setStatus(Node.NodeStatus.FAILED);
                return;
            }

            node.setStatus(Node.NodeStatus.FAILED);
            if (webSocketHandler != null) {
                webSocketHandler.sendError(schemaId, node.getId(), e.getMessage());
                webSocketHandler.sendLog(schemaId, "error",
                        "Ошибка выполнения узла: " + e.getMessage(), node.getId());
            }
            log.error("Ошибка выполнения узла {}: {}", node.getId(), e.getMessage(), e);
        }
    }

    // ────────────────────────── agent nodes ──────────────────────────

    private String executeAgentNode(Node node, String schemaId, String resolvedModel) {
        boolean useTools = node.getData() != null && node.getData().getEnabledTools() != null
                && !node.getData().getEnabledTools().isEmpty();

        if (useTools) {
            return executeToolAgentNode(node, schemaId, resolvedModel);
        }

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "Отправка запроса к AI");
            webSocketHandler.sendLog(schemaId, "info", "Отправка запроса к LLM", node.getId());
        }

        String prompt = node.getData() != null && node.getData().getUserPrompt() != null
                ? node.getData().getUserPrompt()
                : "Анализируй данные";
        String systemPrompt = node.getData() != null ? node.getData().getSystemPrompt() : null;

        String model = resolvedModel;
        if (model == null) {
            model = resolveModel(node.getData() != null ? node.getData().getModel() : null,
                    null, null, null);
        }

        WorkflowSchema currentSchema = schemaRepository.findById(schemaId);
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

        // Inject project context if schema has targetPath
        if (currentSchema != null && currentSchema.getTargetPath() != null && !currentSchema.getTargetPath().isBlank()) {
            try {
                String projectContext = projectContextBuilder.buildContext(
                        currentSchema.getTargetPath(), currentSchema.getWorkspaceId());
                if (!projectContext.isEmpty()) {
                    systemPrompt = (systemPrompt != null ? systemPrompt + "\n\n" : "") +
                            "=== Project Context ===\n" + projectContext;
                }
            } catch (Exception e) {
                log.warn("Failed to build project context: {}", e.getMessage());
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

        // Extract generatedFiles JSON from agent response
        if (result != null && !result.isBlank()) {
            Map<String, Object> extracted = extractGeneratedFiles(result);
            if (extracted != null) {
                generatedFilesRegistry.put(schemaId + ":" + node.getId(), extracted);
                log.info("Extracted generatedFiles from agent node {}: {}", node.getId(), extracted);
            }
        }

        if (memPalaceClient.isEnabled() && result != null && !result.isBlank()) {
            memPalaceClient.addDrawer("axolotl", "agent-results",
                    node.getName() + ": " + result.substring(0, Math.min(500, result.length())),
                    "schema:" + schemaId);
        }

        return result;
    }

    private String executeToolAgentNode(Node node, String schemaId, String resolvedModel) {
        String agentType = node.getData().getAgentType();
        List<String> enabledTools = node.getData().getEnabledTools();
        int maxToolCalls = node.getData().getMaxToolCalls() > 0 ? node.getData().getMaxToolCalls() : 10;

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 10, "Инициализация агента с инструментами");
            webSocketHandler.sendLog(schemaId, "info", "Агент типа: " + agentType + ", инструменты: " + enabledTools, node.getId());
        }

        String prompt = node.getData().getUserPrompt();
        String systemPrompt = node.getData().getSystemPrompt();

        WorkflowSchema currentSchema = schemaRepository.findById(schemaId);
        String model = resolvedModel;
        if (model == null) {
            model = resolveModel(node.getData().getModel(), null, null, null);
        }
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

        // Inject project context if schema has targetPath
        if (currentSchema != null && currentSchema.getTargetPath() != null && !currentSchema.getTargetPath().isBlank()) {
            try {
                String projectContext = projectContextBuilder.buildContext(
                        currentSchema.getTargetPath(), currentSchema.getWorkspaceId());
                if (!projectContext.isEmpty()) {
                    systemPrompt += "\n\n=== Project Context ===\n" + projectContext;
                }
            } catch (Exception e) {
                log.warn("Failed to build project context: {}", e.getMessage());
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
                @SuppressWarnings("unchecked")
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

        // Extract generatedFiles JSON from agent response
        String finalResponse = lastResponse != null ? lastResponse : fullResponse.toString();
        if (finalResponse != null && !finalResponse.isBlank()) {
            Map<String, Object> extracted = extractGeneratedFiles(finalResponse);
            if (extracted != null) {
                generatedFilesRegistry.put(schemaId + ":" + node.getId(), extracted);
                log.info("Extracted generatedFiles from tool agent node {}: {}", node.getId(), extracted);
            }
        }

        return finalResponse;
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

    // ────────────────────────── output / command / filewrite ──────────────────────────

    private String executeOutputNode(Node node, String schemaId, ExecutionMode mode) {
        if (mode == ExecutionMode.ANALYZE || mode == ExecutionMode.DRY_RUN) {
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "warning", "Блокировка: запись не выполняется в режиме " + mode, node.getId());
            }
            return "[SIMULATED] Output node - no file/memory operations";
        }

        Map<String, Object> config = node.getData() != null && node.getData().getConfig() != null
                ? node.getData().getConfig() : new HashMap<>();
        String outputMode = config.get("mode") instanceof String
                ? (String) config.get("mode") : "simple";

        var predResults = collectPredecessorResults(schemaRepository.findById(schemaId), node.getId());
        String input = predResults.values().stream().findFirst().map(Object::toString).orElse("");

        // ── Summary Report Mode ──
        if ("summary_report".equals(outputMode)) {
            return executeSummaryReportNode(node, schemaId, config, input);
        }

        // ── Simple / Default mode (existing behavior) ──
        String outputType = config.getOrDefault("outputType", "log") instanceof String
                ? (String) config.get("outputType") : "log";

        if ("memory".equals(outputType)) {
            String wing = config.getOrDefault("memoryWing", "axolotl") instanceof String
                    ? (String) config.get("memoryWing") : "axolotl";
            String room = config.getOrDefault("memoryRoom", "agent-results") instanceof String
                    ? (String) config.get("memoryRoom") : "agent-results";
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

        String filePath = config.getOrDefault("filePath", "") instanceof String
                ? (String) config.get("filePath") : "";
        String fileFormat = config.getOrDefault("fileFormat", "text") instanceof String
                ? (String) config.get("fileFormat") : "text";
        String result = writeOutput(outputType, filePath, fileFormat, input);
        if ("file".equals(outputType) && filePath != null && !filePath.isBlank()) {
            outputFileRegistry.put(schemaId + ":" + node.getId(), filePath);
        }
        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "info", "Output: " + result, node.getId());
        }
        return result;
    }

    /**
     * Execute output node in summary_report mode.
     * Builds a structured markdown report from all completed nodes' results.
     */
    private String executeSummaryReportNode(Node node, String schemaId, Map<String, Object> config, String input) {
        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 20, "Building summary report");
            webSocketHandler.sendLog(schemaId, "info", "Building summary report", node.getId());
        }

        boolean includeReview = config.getOrDefault("includeReview", true) instanceof Boolean
                ? (Boolean) config.get("includeReview") : true;
        boolean includeFiles = config.getOrDefault("includeFiles", true) instanceof Boolean
                ? (Boolean) config.get("includeFiles") : true;
        boolean includeVerification = config.getOrDefault("includeVerification", true) instanceof Boolean
                ? (Boolean) config.get("includeVerification") : true;
        boolean includeMetrics = config.getOrDefault("includeMetrics", true) instanceof Boolean
                ? (Boolean) config.get("includeMetrics") : true;
        String reportPath = config.getOrDefault("reportPath", "pipeline-report.md") instanceof String
                ? (String) config.get("reportPath") : "pipeline-report.md";

        // Collect all node results for this execution
        Map<String, String> nodeResultsMap = nodeResults.getOrDefault(schemaId, new ConcurrentHashMap<>());
        WorkflowSchema currentSchema = schemaRepository.findById(schemaId);

        StringBuilder report = new StringBuilder();
        report.append("# Pipeline Summary\n\n");

        // ── Plan (from Review) ──
        if (includeReview) {
            report.append("## Plan (from Review)\n\n");
            // Find review node results
            if (currentSchema != null && currentSchema.getNodes() != null) {
                for (Node n : currentSchema.getNodes()) {
                    if ("review".equals(n.getType())) {
                        String reviewResult = nodeResultsMap.get(n.getId());
                        if (reviewResult != null && !reviewResult.isBlank()) {
                            try {
                                ObjectMapper mapper = new ObjectMapper();
                                JsonNode root = mapper.readTree(reviewResult);
                                int findingsCount = root.has("findings") && root.get("findings").isArray()
                                        ? root.get("findings").size() : 0;
                                String approvedBy = "auto";
                                if (root.has("status")) {
                                    String status = root.get("status").asText();
                                    if ("AWAITING_APPROVAL".equals(status)) {
                                        approvedBy = "pending";
                                    } else if ("PASS".equals(status)) {
                                        approvedBy = "human";
                                    }
                                }
                                int highCount = 0, medCount = 0;
                                if (root.has("findings") && root.get("findings").isArray()) {
                                    for (JsonNode f : root.get("findings")) {
                                        String sev = f.has("severity") ? f.get("severity").asText() : "";
                                        if ("critical".equals(sev) || "high".equals(sev)) highCount++;
                                        else if ("warning".equals(sev) || "medium".equals(sev)) medCount++;
                                    }
                                }
                                report.append("- Iterations: ").append(root.has("rewriteIterations") ? root.get("rewriteIterations").asText() : "1").append("\n");
                                report.append("- Approved by: ").append(approvedBy).append("\n");
                                report.append("- Findings: ").append(findingsCount)
                                        .append(" (").append(highCount).append(" HIGH, ").append(medCount).append(" MEDIUM)\n");
                                String planText = root.has("plan") ? root.get("plan").asText() : "";
                                if (planText.length() > 500) planText = planText.substring(0, 500) + "...";
                                report.append("- Plan: ").append(planText).append("\n");
                            } catch (Exception e) {
                                report.append("- Review result: ").append(reviewResult.length() > 200 ? reviewResult.substring(0, 200) + "..." : reviewResult).append("\n");
                            }
                        } else {
                            report.append("- No review data available\n");
                        }
                        break;
                    }
                }
            }
            report.append("\n");
        }

        // ── Agent (from Think/Agent nodes) ──
        if (includeFiles && currentSchema != null && currentSchema.getNodes() != null) {
            report.append("## Agent (from Think)\n\n");
            for (Node n : currentSchema.getNodes()) {
                if ("agent".equals(n.getType())) {
                    String agentResult = nodeResultsMap.get(n.getId());
                    String model = n.getData() != null ? n.getData().getModel() : "unknown";
                    report.append("- Model: ").append(model != null ? model : "unknown").append("\n");

                    // Check generated files
                    String genKey = schemaId + ":" + n.getId();
                    Object genFiles = generatedFilesRegistry.get(genKey);
                    if (genFiles instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> files = (Map<String, Object>) genFiles;
                        report.append("- Generated files: ");
                        for (Map.Entry<String, Object> fe : files.entrySet()) {
                            report.append(fe.getKey()).append(" (").append(fe.getValue()).append(" lines), ");
                        }
                        if (report.charAt(report.length() - 2) == ',') {
                            report.setLength(report.length() - 2);
                        }
                        report.append("\n");
                    } else if (agentResult != null) {
                        int approxLines = agentResult.split("\n").length;
                        report.append("- Output lines: ").append(approxLines).append("\n");
                    }
                    break;
                }
            }
            report.append("\n");
        }

        // ── Verification ──
        if (includeVerification && currentSchema != null && currentSchema.getNodes() != null) {
            report.append("## Verification\n\n");
            for (Node n : currentSchema.getNodes()) {
                if ("verifier".equals(n.getType())) {
                    String verResult = nodeResultsMap.get(n.getId());
                    if (verResult != null && !verResult.isBlank()) {
                        try {
                            ObjectMapper mapper = new ObjectMapper();
                            JsonNode root = mapper.readTree(verResult);
                            String verStatus = root.has("status") ? root.get("status").asText() : "UNKNOWN";
                            report.append("- Syntax check: ").append(verStatus.equals("PASS") ? "PASS" : "FAIL").append("\n");

                            if (root.has("premortemPredictions") && root.get("premortemPredictions").isArray()) {
                                int predCount = root.get("premortemPredictions").size();
                                report.append("- Premortem predictions: ").append(predCount);
                                if (root.has("checkResults") && root.get("checkResults").isArray()) {
                                    int confirmed = 0;
                                    for (JsonNode cr : root.get("checkResults")) {
                                        if (cr.has("passed") && !cr.get("passed").asBoolean()) confirmed++;
                                    }
                                    report.append(", Confirmed: ").append(confirmed)
                                            .append(", Fixed: ").append(confirmed);
                                }
                                report.append("\n");
                            }

                            int rewrites = root.has("rewriteRetries") ? root.get("rewriteRetries").asInt() : 0;
                            report.append("- Rewrite retries: ").append(rewrites).append("\n");

                            if (root.has("checkResults") && root.get("checkResults").isArray()) {
                                for (JsonNode cr : root.get("checkResults")) {
                                    String name = cr.has("name") ? cr.get("name").asText() : "check";
                                    boolean passed = !cr.has("passed") || cr.get("passed").asBoolean();
                                    report.append("  - ").append(name).append(": ").append(passed ? "PASS" : "FAIL").append("\n");
                                }
                            }
                        } catch (Exception e) {
                            report.append("- Verification result available\n");
                        }
                    } else {
                        report.append("- No verification data\n");
                    }
                    break;
                }
            }
            report.append("\n");
        }

        // ── Execution Metrics ──
        if (includeMetrics) {
            report.append("## Execution\n\n");
            // Estimate total time from node execution times if available
            int totalNodes = currentSchema != null && currentSchema.getNodes() != null
                    ? currentSchema.getNodes().size() : 0;
            int completedNodes = 0;
            if (currentSchema != null && currentSchema.getNodes() != null) {
                for (Node n : currentSchema.getNodes()) {
                    if (n.getStatus() == Node.NodeStatus.COMPLETED) {
                        completedNodes++;
                    }
                }
            }
            report.append("- Total nodes: ").append(totalNodes).append("\n");
            report.append("- Completed nodes: ").append(completedNodes).append("\n");
            if (input != null && !input.isBlank()) {
                // Try to extract time from input if it contains duration info
                if (input.contains("Total time") || input.contains("totalTime")) {
                    report.append("- Time info available in upstream\n");
                }
            }
            report.append("\n");
        }

        report.append("---\n*Generated by Axolotl Output Node (summary_report mode)*\n");

        String reportContent = report.toString();

        // Write to file
        try {
            // Determine target directory
            String targetDir = ".";
            if (currentSchema != null && currentSchema.getTargetPath() != null && !currentSchema.getTargetPath().isBlank()) {
                targetDir = currentSchema.getTargetPath();
            }
            Path reportFilePath = Path.of(targetDir, reportPath).normalize();
            Files.createDirectories(reportFilePath.getParent());
            Files.writeString(reportFilePath, reportContent);

            // Register in output file registry
            outputFileRegistry.put(schemaId + ":" + node.getId(), reportFilePath.toString());

            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "success",
                        "Summary report written to: " + reportFilePath, node.getId());
            }

            return "Summary report written to: " + reportFilePath + "\n\n" + reportContent;
        } catch (Exception e) {
            log.error("Failed to write summary report: {}", e.getMessage(), e);
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "error",
                        "Failed to write summary report: " + e.getMessage(), node.getId());
            }
            return "Summary report (write failed, returning inline):\n\n" + reportContent;
        }
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
                node.getData().setConfig(Map.of("exitCode", exitCode));
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

        String normalizedPath = Path.of(filePath).toAbsolutePath().normalize().toString();
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

    // ────────────────────────── subagent / schema-builder ──────────────────────────

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

    // ── SchemaBuilder static prompts ──

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

    private String executeSchemaBuilderNode(Node node, String schemaId, String resolvedModel) {
        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 20, "Building schema from agent result");
            webSocketHandler.sendLog(schemaId, "info", "SchemaBuilder: generating workflow", node.getId());
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

        String model = resolvedModel;
        if (model == null) {
            model = resolveModel(node.getData() != null ? node.getData().getModel() : null,
                    null, null, null);
        }
        String llmResponse = llmService.chat(model, SCHEMA_BUILDER_SYSTEM_PROMPT, input, null);

        if (llmResponse == null || llmResponse.isBlank()
                || llmResponse.startsWith("Error:") || llmResponse.startsWith("Ollama")) {
            return "Error: LLM call failed — " + (llmResponse != null ? llmResponse : "empty response");
        }

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

        // Save the new schema directly via repository
        String id = UUID.randomUUID().toString();
        newSchema.setId(id);
        newSchema.setCreatedAt(Instant.now().toString());
        newSchema.setUpdatedAt(Instant.now().toString());
        schemaRepository.save(newSchema);
        WorkflowSchema saved = newSchema;

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 75, "Creating plan tasks");
        }

        // Create a SUBSCHEMA plan linked to the generated schema
        try {
            Plan parentPlan = planService.getPlan("default");
            Plan subPlan = planService.importSchemaAsSubPlan("default", parentPlan.getId(), saved.getId());
            subPlan.setName(saved.getName());
            planService.updatePlan(subPlan);

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
                    "SchemaBuilder: created '" + saved.getName() + "' (" + nodes.size() + " nodes)", node.getId());
        }

        return "Schema created: " + saved.getName() + " (ID: " + saved.getId() + ", " + nodes.size() + " nodes, " + edges.size() + " edges)";
    }

    // ────────────────────────── condition evaluation ──────────────────────────

    boolean evaluateCondition(String expression, Map<String, Object> context) {
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

    // ────────────────────────── command sanitization ──────────────────────────

    private static final java.util.Set<String> BLOCKED_COMMAND_PATTERNS = java.util.Set.of(
            "rm -rf /", "mkfs", "dd if=", ":(){ :|:&", "> /dev/sd", "format ", "del /f /s /q c:",
            "shutdown", "reboot", "init 0", "init 6", "halt", "poweroff");

    String sanitizeCommand(String command) {
        String lower = command.toLowerCase().trim();
        for (String blocked : BLOCKED_COMMAND_PATTERNS) {
            if (lower.contains(blocked.toLowerCase())) {
                throw new SecurityException("Command blocked: contains dangerous pattern '" + blocked + "'");
            }
        }
        if (lower.contains("$(rm ") || lower.contains("`rm ") || lower.contains("/dev/null >")) {
            throw new SecurityException("Command blocked: contains dangerous shell expansion");
        }
        return command;
    }

    // ────────────────────────── URL validation ──────────────────────────

    void validateUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
                throw new SecurityException("Only http/https URLs allowed");
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                throw new SecurityException("URL must have a valid host");
            }
            InetAddress address = InetAddress.getByName(host);
            if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
                throw new SecurityException("Access to internal network addresses is blocked");
            }
        } catch (java.net.UnknownHostException e) {
            throw new SecurityException("Cannot resolve host: " + e.getMessage());
        }
    }

    // ────────────────────────── path sandbox ──────────────────────────

    boolean isPathAllowed(String filePath) {
        if (allowedWriteDirs == null || allowedWriteDirs.isEmpty()) return true;
        try {
            Path resolved = Path.of(filePath).toAbsolutePath().normalize();
            for (String dir : allowedWriteDirs) {
                Path allowedBase = Path.of(dir).toAbsolutePath().normalize();
                if (resolved.startsWith(allowedBase)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ────────────────────────── URL fetch ──────────────────────────

    String fetchUrlContent(String url) {
        try {
            validateUrl(url);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String content = response.body();
                return content.length() > 50000 ? content.substring(0, 50000) : content;
            }
            return "Ошибка загрузки URL: HTTP " + response.statusCode();
        } catch (Exception e) {
            return "Ошибка загрузки URL: " + e.getMessage();
        }
    }

    // ────────────────────────── project context ──────────────────────────

    String readProjectContext(String projectPath, Map<String, Object> config) {
        try {
            Path root = Path.of(projectPath);
            if (!Files.exists(root)) {
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

            sb.append("=== FILE TREE ===\n");
            List<String> files = new ArrayList<>();
            Files.walk(root, maxDepth)
                    .filter(p -> {
                        for (int i = 0; i < p.getNameCount(); i++) {
                            if (excludeDirs.contains(p.getName(i).toString())) return false;
                        }
                        return true;
                    })
                    .filter(Files::isRegularFile)
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

            sb.append("=== KEY FILES ===\n");
            List<String> priorityFiles = List.of("README.md", "CLAUDE.md", "package.json", "pom.xml", "Cargo.toml", "go.mod", "pyproject.toml");
            for (String pf : priorityFiles) {
                Path p = root.resolve(pf);
                if (Files.exists(p)) {
                    String content = Files.readString(p);
                    sb.append("\n--- ").append(pf).append(" ---\n");
                    sb.append(content.length() > 3000 ? content.substring(0, 3000) + "\n... (truncated)" : content);
                    sb.append("\n");
                }
            }

            boolean includeSources = config == null || !Boolean.FALSE.equals(config.get("includeSources"));
            if (includeSources) {
                sb.append("\n=== SOURCE FILES ===\n");
                int fileCount = 0;
                for (String f : files) {
                    if (fileCount >= 20) break;
                    Path p = root.resolve(f);
                    try {
                        String content = Files.readString(p);
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

    // ────────────────────────── write output ──────────────────────────

    String writeOutput(String outputType, String filePath, String fileFormat, String content) {
        if (content == null || content.isBlank()) {
            return "Нет данных для вывода";
        }
        if ("file".equals(outputType) && filePath != null && !filePath.isBlank()) {
            try {
                Path path = Path.of(filePath);
                Files.createDirectories(path.getParent());
                String dataToWrite = content;
                if ("json".equals(fileFormat)) {
                    dataToWrite = "{\n  \"result\": " + new ObjectMapper().writeValueAsString(content) + ",\n  \"timestamp\": " + System.currentTimeMillis() + "\n}";
                }
                Files.writeString(path, dataToWrite);
                return "Сохранено в файл: " + filePath;
            } catch (Exception e) {
                return "Ошибка записи файла: " + e.getMessage();
            }
        }
        return content;
    }

    // ────────────────────────── tool helpers ──────────────────────────

    String buildToolDefinitions(List<String> toolIds) {
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

    String buildToolInstructions(List<String> toolIds) {
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

    String buildMessagesForToolCall(List<Node.Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Node.Message msg : messages) {
            sb.append("<message role=\"").append(msg.getRole()).append("\">\n");
            sb.append(msg.getContent()).append("\n</message>\n");
        }
        return sb.toString();
    }

    List<Map<String, Object>> parseToolCalls(String response) {
        List<Map<String, Object>> calls = new ArrayList<>();
        if (response == null || !response.contains("tool_calls")) {
            return calls;
        }

        try {
            // 1. Strip markdown code blocks
            String json = response;
            if (json.contains("```")) {
                json = json.replaceAll("```json\\s*", "");
                json = json.replaceAll("```\\s*", "");
                json = json.trim();
            }

            // 2. Find tool_calls section using brace-depth matching (string-aware)
            int toolCallsIdx = json.indexOf("\"tool_calls\"");
            if (toolCallsIdx < 0) {
                toolCallsIdx = json.indexOf("tool_calls");
            }
            if (toolCallsIdx < 0) return calls;

            int arrayStart = json.indexOf("[", toolCallsIdx);
            if (arrayStart < 0) return calls;

            // Match the closing bracket respecting string boundaries
            int arrayEnd = findMatchingBracket(json, arrayStart);
            if (arrayEnd < 0) return calls;

            String toolsJson = json.substring(arrayStart, arrayEnd + 1);

            // 3. Try strict JSON parsing first
            ObjectMapper mapper = new ObjectMapper();
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> parsed = mapper.readValue(toolsJson, List.class);
                calls.addAll(parsed);
                return calls;
            } catch (Exception e) {
                log.debug("Strict JSON parse failed: {}", e.getMessage());
            }

            // 4. Try lenient parsing (unescaped chars, single quotes)
            try {
                ObjectMapper lenientMapper = new ObjectMapper()
                        .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
                        .configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true)
                        .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> parsed = lenientMapper.readValue(toolsJson, List.class);
                calls.addAll(parsed);
                return calls;
            } catch (Exception e) {
                log.debug("Lenient JSON parse failed: {}", e.getMessage());
            }

            // 5. Fallback: extract individual tool calls by brace-depth within the array,
            //    then try to parse each one separately with lenient mapper
            ObjectMapper lenientMapper = new ObjectMapper()
                    .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
                    .configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true)
                    .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true);

            List<String> toolCallObjects = extractTopLevelObjects(toolsJson);
            for (String obj : toolCallObjects) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = lenientMapper.readValue(obj, Map.class);
                    calls.add(parsed);
                } catch (Exception e) {
                    log.debug("Failed to parse individual tool call, trying regex fallback: {}", e.getMessage());
                    // 6. Regex fallback: extract name + path from malformed JSON
                    Map<String, Object> fallbackCall = extractToolCallWithRegex(obj);
                    if (fallbackCall != null && fallbackCall.get("name") != null) {
                        fallbackCall.putIfAbsent("id", "call_" + calls.size());
                        calls.add(fallbackCall);
                    }
                }
            }

            if (calls.isEmpty()) {
                // Log diagnostic info if all fallbacks failed
                String diag = toolsJson.length() > 200 ? toolsJson.substring(0, 200) + "..." : toolsJson;
                log.warn("All tool call parsing fallbacks exhausted. Response snippet: {}", diag);
            }

        } catch (Exception e) {
            log.warn("Failed to parse tool calls: {}", e.getMessage());
        }
        return calls;
    }

    /**
     * Find the matching closing bracket for array at position startIdx,
     * respecting string boundaries (handles escaped quotes within strings).
     */
    private int findMatchingBracket(String json, int startIdx) {
        char openBracket = json.charAt(startIdx);
        char closeBracket = (openBracket == '[') ? ']' : '}';
        int depth = 0;
        boolean inString = false;

        for (int i = startIdx; i < json.length(); i++) {
            char c = json.charAt(i);

            if (inString) {
                if (c == '\\') {
                    i++; // skip escaped character
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }

            if (c == openBracket) {
                depth++;
            } else if (c == closeBracket) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Extract top-level JSON objects from a JSON array string.
     * Handles potentially malformed JSON by brace-depth matching.
     */
    private List<String> extractTopLevelObjects(String jsonArray) {
        List<String> objects = new ArrayList<>();
        int i = 0;
        while (i < jsonArray.length()) {
            // Skip non-object chars (whitespace, commas, brackets)
            char c = jsonArray.charAt(i);
            if (c == '{') {
                int end = findMatchingBracket(jsonArray, i);
                if (end > i) {
                    objects.add(jsonArray.substring(i, end + 1));
                    i = end + 1;
                } else {
                    i++;
                }
            } else {
                i++;
            }
        }
        return objects;
    }

    /**
     * Regex-based fallback: extract tool name, arguments (path, content)
     * from malformed JSON that couldn't be parsed by Jackson.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractToolCallWithRegex(String text) {
        Map<String, Object> call = new HashMap<>();

        // Extract name field
        Pattern namePat = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
        Matcher nameMatcher = namePat.matcher(text);
        if (nameMatcher.find()) {
            call.put("name", nameMatcher.group(1));
        }

        // Find arguments section
        int argStart = text.indexOf("\"arguments\"");
        if (argStart < 0) {
            argStart = text.indexOf("arguments");
        }
        if (argStart > 0) {
            int objStart = text.indexOf("{", argStart);
            if (objStart > 0) {
                int objEnd = findMatchingBracket(text, objStart);
                if (objEnd > objStart) {
                    String argsJson = text.substring(objStart, objEnd + 1);
                    try {
                        ObjectMapper lenientMapper = new ObjectMapper()
                                .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
                                .configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true);
                        Map<String, Object> args = lenientMapper.readValue(argsJson, Map.class);
                        call.put("arguments", args);
                    } catch (Exception e) {
                        // Last resort: extract path via regex, content via grab
                        Map<String, Object> fallbackArgs = extractArgumentsWithRegex(argsJson);
                        call.put("arguments", fallbackArgs);
                    }
                }
            }
        }

        return call.isEmpty() ? null : call;
    }

    /**
     * Extract path and content from arguments using regex.
     * For content, grabs everything between "content":" and the last " before }.
     */
    private Map<String, Object> extractArgumentsWithRegex(String argsJson) {
        Map<String, Object> args = new HashMap<>();

        // Extract path (clean string)
        Pattern pathPat = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"");
        Matcher pathMatcher = pathPat.matcher(argsJson);
        if (pathMatcher.find()) {
            args.put("path", pathMatcher.group(1));
        }

        // Extract content (fuzzy — grab from "content":" to last ", before close)
        int contentKeyStart = argsJson.indexOf("\"content\"");
        if (contentKeyStart < 0) {
            contentKeyStart = argsJson.indexOf("content");
        }
        if (contentKeyStart > 0) {
            int colonIdx = argsJson.indexOf(":", contentKeyStart);
            int quoteStart = argsJson.indexOf("\"", colonIdx);
            if (quoteStart > colonIdx) {
                // Find the last " before the closing }}
                int endMarker = argsJson.lastIndexOf("\"");
                if (endMarker > quoteStart) {
                    String content = argsJson.substring(quoteStart + 1, endMarker);
                    // Unescape common JSON escapes
                    content = content.replace("\\n", "\n").replace("\\t", "\t")
                                     .replace("\\\"", "\"").replace("\\\\", "\\");
                    args.put("content", content);
                }
            }
        }

        return args;
    }

    String executeToolCall(String toolId, Map<String, Object> args, Node node, String schemaId) {
        ToolPermission permission = null;
        if (node.getData().getToolPermissions() != null) {
            for (ToolPermission tp : node.getData().getToolPermissions()) {
                if (tp.getToolId() != null && tp.getToolId().equals(toolId)) {
                    permission = tp;
                    break;
                }
            }
        }

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

    void sendUserApprovalRequest(String schemaId, String nodeId, int toolCallCount, int maxToolCalls) {
        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "warning",
                    "Достигнут лимит инструментов (" + toolCallCount + "/" + maxToolCalls + "). Требуется подтверждение для продолжения.",
                    nodeId);
        }
    }

    // ────────────────────────── utilities ──────────────────────────

    boolean sleepWithCancel(long millis, AtomicBoolean cancelFlag) {
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

    Map<String, Object> collectPredecessorResults(WorkflowSchema schema, String nodeId) {
        Map<String, Object> results = new HashMap<>();
        if (schema.getEdges() == null || schema.getNodes() == null) {
            return results;
        }
        Map<String, String> cached = nodeResults.getOrDefault(schema.getId(), Map.of());

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

    String buildContextBlock(Map<String, Object> predecessorResults) {
        if (predecessorResults.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        predecessorResults.forEach((name, value) -> {
            sb.append("[").append(name).append("]: ").append(value).append("\n");
        });
        String context = sb.toString().trim();

        if (context.length() > MAX_CONTEXT_CHARS) {
            log.info("Сжатие контекста: {} символов → суммаризация", context.length());
            try {
                String summary = llmService.chat("ollama",
                        "Ты компрессор контекста. Сжато передай суть, сохранив ключевые факты, числа, имена.",
                        "Сожми следующий контекст, сохранив ключевые факты:\n\n" + context,
                        null);
                return "[СЖАТЫЙ КОНТЕКСТ]:\n" + summary;
            } catch (Exception e) {
                return context.substring(0, MAX_CONTEXT_CHARS) + "\n... [контекст обрезан]";
            }
        }

        return context;
    }

    String interpolateVariables(String text, WorkflowSchema schema, Map<String, Object> predecessorResults) {
        if (text == null || !text.contains("{{")) return text;

        String input = predecessorResults.values().stream().findFirst().map(Object::toString).orElse("");
        text = text.replace("{{input}}", input);

        String prevResult = predecessorResults.values().stream()
                .reduce((first, second) -> second).map(Object::toString).orElse("");
        text = text.replace("{{prev_result}}", prevResult);

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

    /**
     * Simplified model resolution — takes pre-fetched values.
     * Falls back: nodeModel -> schemaModel -> userId -> globalModel -> null.
     */
    String resolveModel(String nodeModel, String schemaModel, String userId, String globalModel) {
        if (nodeModel != null && !nodeModel.isBlank()) return nodeModel;
        if (schemaModel != null && !schemaModel.isBlank()) return schemaModel;
        if (globalModel != null && !globalModel.isBlank()) return globalModel;
        return null;
    }

    // ────────────────────────── generatedFiles extraction ──────────────────────────

    /**
     * Look for a top-level {@code {"generatedFiles": [...]}} JSON block in the last 500 characters
     * of the LLM response. Returns the parsed map, or null if not found / not parseable.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> extractGeneratedFiles(String response) {
        if (response == null || response.isBlank()) return null;
        String tail = response.substring(Math.max(0, response.length() - 500));
        int idx = tail.lastIndexOf("\"generatedFiles\"");
        if (idx < 0) return null;
        // walk back to find the opening '{'
        int brace = tail.lastIndexOf('{', idx);
        if (brace < 0) return null;
        String candidate = tail.substring(brace);
        // close at the first matching '}'
        int depth = 0;
        int close = -1;
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) { close = i; break; }
            }
        }
        if (close < 0) return null;
        String json = candidate.substring(0, close + 1);
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.debug("Failed to parse generatedFiles JSON from agent response: {}", e.getMessage());
            return null;
        }
    }

    // ────────────────────────── verifier nodes ──────────────────────────

    private String executeVerifierNode(Node node, String schemaId, String resolvedModel) {
        // Collect predecessor results (the generated file content from upstream)
        Map<String, Object> predResults = collectPredecessorResults(
                schemaRepository.findById(schemaId), node.getId());
        String inputContent = predResults.values().stream()
                .findFirst().map(Object::toString).orElse("");

        // Extract verification rules from node config
        Map<String, Object> config = node.getData() != null && node.getData().getConfig() != null
                ? node.getData().getConfig() : new HashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> checks = config.get("checks") instanceof Map
                ? (Map<String, Object>) config.get("checks") : new HashMap<>();

        boolean syntaxCheck = checks.get("syntaxCheck") instanceof Boolean
                ? (Boolean) checks.get("syntaxCheck") : true;
        @SuppressWarnings("unchecked")
        List<String> requiredPatterns = checks.get("requiredPatterns") instanceof List
                ? (List<String>) checks.get("requiredPatterns") : List.of();
        String testCommand = checks.get("testCommand") instanceof String
                ? (String) checks.get("testCommand") : "";
        int maxFileSizeKb = checks.get("maxFileSizeKb") instanceof Number
                ? ((Number) checks.get("maxFileSizeKb")).intValue() : 500;

        // New config: rewrite on fail
        boolean rewriteOnFail = config.get("rewriteOnFail") instanceof Boolean
                ? (Boolean) config.get("rewriteOnFail") : false;
        int maxRewriteRetries = config.get("maxRewriteRetries") instanceof Number
                ? ((Number) config.get("maxRewriteRetries")).intValue() : 3;
        boolean premortemEnabled = checks.get("premortem") instanceof Boolean
                ? (Boolean) checks.get("premortem") : false;

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 10, "Запуск верификации");
            webSocketHandler.sendLog(schemaId, "info", "Верификация: синтаксис=" + syntaxCheck
                    + ", паттерны=" + requiredPatterns + ", тест=" + testCommand
                    + ", rewriteOnFail=" + rewriteOnFail + ", maxRetries=" + maxRewriteRetries
                    + ", premortem=" + premortemEnabled, node.getId());
        }

        String model = resolvedModel;
        if (model == null) {
            model = resolveModel(node.getData() != null ? node.getData().getModel() : null,
                    null, null, null);
        }

        // Step 1: Premortem predictions (if enabled)
        List<String> premortemPredictions = new ArrayList<>();
        if (premortemEnabled) {
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "info", "Premortem: predicting failure scenarios", node.getId());
            }
            String premortemPrompt = "Analyze the following code and predict potential failure scenarios, bugs, or issues. "
                    + "List each prediction as a separate line starting with '- '\n\nCode:\n" + inputContent;
            String premortemResult = llmService.chat(model, null, premortemPrompt, null);
            if (premortemResult != null) {
                for (String line : premortemResult.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("- ")) {
                        premortemPredictions.add(trimmed.substring(2).trim());
                    }
                }
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schemaId, "info",
                            "Premortem predictions: " + premortemPredictions.size(), node.getId());
                }
            }
        }

        // Steps 2-6: Run checks with optional rewrite loop
        List<Map<String, Object>> allCheckResults = new ArrayList<>();
        int rewriteRetries = 0;
        String currentContent = inputContent;
        String currentResult = "";

        while (rewriteRetries <= maxRewriteRetries) {
            // Build verification prompt for this iteration
            StringBuilder verificationPrompt = new StringBuilder();
            verificationPrompt.append("Ты — верификатор кода. Проверь сгенерированный файл на ошибки.\n\n");
            verificationPrompt.append("Инструкции:\n");
            verificationPrompt.append("1. Сначала прочитай содержимое файла через file_read\n");
            if (syntaxCheck) {
                verificationPrompt.append("2. Запусти синтаксическую проверку: bash 'python3 -m py_compile <filepath>'\n");
            }
            if (!requiredPatterns.isEmpty()) {
                verificationPrompt.append("3. Проверь наличие обязательных паттернов через bash grep:\n");
                for (String pattern : requiredPatterns) {
                    verificationPrompt.append("   - \"").append(pattern).append("\"\n");
                }
            }
            if (testCommand != null && !testCommand.isBlank()) {
                verificationPrompt.append("4. Запусти тестовую команду: bash '").append(testCommand).append("'\n");
            }
            if (premortemEnabled && !premortemPredictions.isEmpty()) {
                verificationPrompt.append("5. Проверь, какие из предсказанных сценариев отказа подтвердились:\n");
                for (String pred : premortemPredictions) {
                    verificationPrompt.append("   - \"").append(pred).append("\"\n");
                }
            }
            verificationPrompt.append("\nФормат ответа (строгий JSON, без markdown):\n");
            verificationPrompt.append("{\n");
            verificationPrompt.append("  \"status\": \"PASS\" или \"FAIL\",\n");
            verificationPrompt.append("  \"checks\": [\n");
            verificationPrompt.append("    {\"name\": \"syntax\", \"passed\": true/false},\n");
            verificationPrompt.append("    {\"name\": \"required_patterns\", \"passed\": true/false, \"found\": [...], \"missing\": [...]},\n");
            verificationPrompt.append("    {\"name\": \"test_command\", \"passed\": true/false, \"error\": \"...\"}\n");
            verificationPrompt.append("  ],\n");
            verificationPrompt.append("  \"summary\": \"Описание результата\"\n");
            verificationPrompt.append("}\n");
            verificationPrompt.append("\nСодержимое файла для проверки:\n").append(currentContent);

            if (rewriteRetries > 0) {
                verificationPrompt.append("\n\n=== Rewrite attempt ").append(rewriteRetries)
                        .append(" of ").append(maxRewriteRetries).append(" ===");
            }

            // Build a temporary NodeData with verifier-specific settings
            Node.NodeData verifierData = node.getData() != null ? node.getData() : new Node.NodeData();
            verifierData.setAgentType("verifier");
            verifierData.setEnabledTools(List.of("file_read", "bash", "grep"));
            verifierData.setMaxToolCalls(10);
            verifierData.setUserPrompt(verificationPrompt.toString());
            verifierData.setSystemPrompt("Ты — верификатор. Проверяй сгенерированный код и возвращай структурированный JSON с результатами проверок.");
            node.setData(verifierData);
            node.setType("agent"); // Temporarily treat as agent for tool execution

            // Execute via tool agent path
            currentResult = executeToolAgentNode(node, schemaId, resolvedModel);

            // Restore verifier type
            node.setType("verifier");

            // Parse result
            boolean allPassed = false;
            StringBuilder errorsBuilder = new StringBuilder();

            if (currentResult != null && !currentResult.isBlank()) {
                try {
                    String jsonStr = currentResult.trim();
                    int jsonStart = jsonStr.indexOf('{');
                    int jsonEnd = jsonStr.lastIndexOf('}');
                    if (jsonStart >= 0 && jsonEnd > jsonStart) {
                        jsonStr = jsonStr.substring(jsonStart, jsonEnd + 1);
                    }

                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode root = mapper.readTree(jsonStr);
                    String status = root.has("status") ? root.get("status").asText() : "PASS";
                    String summary = root.has("summary") ? root.get("summary").asText() : "";

                    // Store check results
                    if (root.has("checks")) {
                        for (JsonNode check : root.get("checks")) {
                            Map<String, Object> checkMap = new HashMap<>();
                            checkMap.put("name", check.has("name") ? check.get("name").asText() : "unknown");
                            checkMap.put("passed", check.has("passed") ? check.get("passed").asBoolean() : false);
                            checkMap.put("iteration", rewriteRetries);
                            allCheckResults.add(checkMap);

                            if (!check.has("passed") || !check.get("passed").asBoolean()) {
                                String errMsg = check.has("name") ? check.get("name").asText() + " failed" : "check failed";
                                if (check.has("error")) {
                                    errMsg += ": " + check.get("error").asText();
                                }
                                errorsBuilder.append(errMsg).append("\n");
                            }
                        }
                    }

                    if ("PASS".equals(status)) {
                        allPassed = true;
                        if (webSocketHandler != null) {
                            webSocketHandler.sendLog(schemaId, "success",
                                    "Верификация PASS: " + summary + (rewriteRetries > 0 ? " (after " + rewriteRetries + " rewrite(s))" : ""), node.getId());
                        }
                        break;
                    }
                } catch (Exception e) {
                    log.warn("Не удалось распарсить результат верификации: {}", e.getMessage());
                }
            }

            // If FAIL and rewriteOnFail is enabled, try to fix
            if (!allPassed && rewriteOnFail && rewriteRetries < maxRewriteRetries) {
                rewriteRetries++;
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schemaId, "warning",
                            "Verification FAIL, attempting rewrite " + rewriteRetries + "/" + maxRewriteRetries, node.getId());
                    webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING",
                            30 + (rewriteRetries * 20), "Rewrite attempt " + rewriteRetries);
                }

                // Call LLM to fix the code
                String fixPrompt = "The following code has issues that need to be fixed.\n\n"
                        + "Original code:\n" + currentContent + "\n\n"
                        + "Errors found:\n" + errorsBuilder.toString() + "\n\n"
                        + "Fix the issues and return ONLY the corrected code. Do NOT include any explanations, markdown fences, or extra text.";

                String fixedCode = llmService.chat(model, null, fixPrompt, null);
                if (fixedCode != null && !fixedCode.isBlank()) {
                    // Clean up the response (remove markdown fences if present)
                    String cleaned = fixedCode.trim();
                    if (cleaned.startsWith("```")) {
                        cleaned = cleaned.replaceFirst("^```\\w*\\n?", "").replaceFirst("\\n?```$", "").trim();
                    }
                    currentContent = cleaned;

                    // Write the fixed code back (if there's a file path in the registry)
                    String prefix = schemaId + ":";
                    for (Map.Entry<String, String> entry : outputFileRegistry.entrySet()) {
                        if (entry.getKey().startsWith(prefix) && entry.getKey().endsWith(":" + node.getId())) {
                            try {
                                Files.writeString(Path.of(entry.getValue()), cleaned);
                                if (webSocketHandler != null) {
                                    webSocketHandler.sendLog(schemaId, "info",
                                            "Fixed code written to: " + entry.getValue(), node.getId());
                                }
                            } catch (Exception e) {
                                log.warn("Failed to write fixed code: {}", e.getMessage());
                            }
                        }
                    }
                }
            } else if (!allPassed) {
                break;
            }
        }

        // Build structured result with premortem predictions and rewrite count
        Map<String, Object> structuredResult = new HashMap<>();
        structuredResult.put("status", allCheckResults.isEmpty() || allCheckResults.stream().allMatch(c -> Boolean.TRUE.equals(c.get("passed"))) ? "PASS" : "FAIL");
        structuredResult.put("premortemPredictions", premortemPredictions);
        structuredResult.put("checkResults", allCheckResults);
        structuredResult.put("rewriteRetries", rewriteRetries);
        structuredResult.put("premortemEnabled", premortemEnabled);

        ObjectMapper mapper = new ObjectMapper();
        String resultStr;
        try {
            resultStr = mapper.writeValueAsString(structuredResult);
        } catch (Exception e) {
            resultStr = currentResult != null ? currentResult : "{\"status\":\"FAIL\",\"summary\":\"Verification error\"}";
        }

        // If all passed or max retries hit, store the structured result
        boolean finalPass = allCheckResults.stream().allMatch(c -> Boolean.TRUE.equals(c.get("passed")));
        if (!finalPass && rewriteRetries >= maxRewriteRetries) {
            node.setStatus(Node.NodeStatus.FAILED);
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "error",
                        "Верификация FAIL after " + rewriteRetries + " rewrite(s)", node.getId());
                webSocketHandler.sendProgress(schemaId, node.getId(), "FAILED", 100,
                        "Failed after " + rewriteRetries + " rewrite(s)");
            }
            nodeResults.computeIfAbsent(schemaId, k -> new ConcurrentHashMap<>())
                    .put(node.getId(), resultStr);
            return resultStr;
        }

        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "success",
                    "Верификация PASS" + (rewriteRetries > 0 ? " after " + rewriteRetries + " rewrite(s)" : ""), node.getId());
        }

        return resultStr;
    }

    // ────────────────────────── review nodes ──────────────────────────

    private String executeReviewNode(Node node, String schemaId, String resolvedModel) {
        // Collect predecessor results (the upstream plan/context)
        Map<String, Object> predResults = collectPredecessorResults(
                schemaRepository.findById(schemaId), node.getId());
        String inputContent = predResults.values().stream()
                .findFirst().map(Object::toString).orElse("");

        // Extract review config from node config
        Map<String, Object> config = node.getData() != null && node.getData().getConfig() != null
                ? node.getData().getConfig() : new HashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> checks = config.get("checks") instanceof Map
                ? (Map<String, Object>) config.get("checks") : new HashMap<>();

        boolean premortem = checks.get("premortem") instanceof Boolean
                ? (Boolean) checks.get("premortem") : true;
        boolean prism = checks.get("prism") instanceof Boolean
                ? (Boolean) checks.get("prism") : false;
        boolean postmortem = checks.get("postmortem") instanceof Boolean
                ? (Boolean) checks.get("postmortem") : false;
        boolean generatePlan = config.get("generatePlan") instanceof Boolean
                ? (Boolean) config.get("generatePlan") : true;
        String mode = config.get("mode") instanceof String
                ? (String) config.get("mode") : "manual";
        int maxAutoIterations = config.get("maxAutoIterations") instanceof Number
                ? ((Number) config.get("maxAutoIterations")).intValue() : 3;

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 10,
                    "Review: collecting context");
            webSocketHandler.sendLog(schemaId, "info",
                    "Review: premortem=" + premortem + ", prism=" + prism
                    + ", postmortem=" + postmortem + ", mode=" + mode
                    + ", generatePlan=" + generatePlan, node.getId());
        }

        String model = resolvedModel;
        if (model == null) {
            model = resolveModel(node.getData() != null ? node.getData().getModel() : null,
                    null, null, null);
        }

        // Check for pending feedback in execution state
        String feedbackKey = schemaId + ":" + node.getId() + ":feedback";
        String pendingFeedback = nodeResults.get(schemaId) != null
                ? nodeResults.get(schemaId).get(feedbackKey) : null;

        // ── Phase 1: Plan Generation ──
        String planText;
        if (generatePlan) {
            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 20,
                        "Review Phase 1: generating plan");
                webSocketHandler.sendLog(schemaId, "info", "Review: generating plan from upstream", node.getId());
            }
            String planPrompt = "Create a detailed implementation plan from this description: " + inputContent;
            if (pendingFeedback != null && !pendingFeedback.isBlank()) {
                planPrompt += "\n\nThe user provided the following feedback on the previous plan: " + pendingFeedback + ". Incorporate it.";
            }
            String planSystemPrompt = "You are a structured planner. Create a clear, detailed implementation plan.";
            planText = llmService.streamingChat(model, planSystemPrompt, planPrompt, null,
                    token -> {
                        if (webSocketHandler != null) {
                            webSocketHandler.sendToken(schemaId, node.getId(), token);
                        }
                    });
        } else {
            planText = inputContent;
        }

        // ── Phase 2: Analysis (premortem/prism/postmortem checks) ──
        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50,
                    "Review Phase 2: analyzing plan");
            webSocketHandler.sendLog(schemaId, "info", "Review: analyzing plan", node.getId());
        }

        StringBuilder reviewPrompt = new StringBuilder();
        reviewPrompt.append("You are a code/plan reviewer. Review the following plan and provide findings.\n\n");

        if (premortem) {
            reviewPrompt.append("=== PREMORTEM CHECK ===\n");
            reviewPrompt.append("Analyze the plan before execution. Identify:\n");
            reviewPrompt.append("- Potential risks and failure points\n");
            reviewPrompt.append("- Missing edge cases\n");
            reviewPrompt.append("- Resource or dependency issues\n");
            reviewPrompt.append("- Timeline or ordering concerns\n\n");
        }

        if (prism) {
            reviewPrompt.append("=== PRISM CHECK (Codebase Context) ===\n");
            reviewPrompt.append("Consider the existing codebase structure, patterns, and conventions.\n");
            reviewPrompt.append("Note any inconsistencies with the established architecture.\n\n");
        }

        if (postmortem) {
            reviewPrompt.append("=== POSTMORTEM CHECK ===\n");
            reviewPrompt.append("Analyze execution history and past outcomes.\n");
            reviewPrompt.append("Consider what went well and what could be improved.\n\n");
        }

        reviewPrompt.append("=== PLAN TO REVIEW ===\n");
        reviewPrompt.append(planText).append("\n\n");

        reviewPrompt.append("=== OUTPUT FORMAT ===\n");
        reviewPrompt.append("Respond in strict JSON format (no markdown fences):\n");
        reviewPrompt.append("{\n");
        reviewPrompt.append("  \"status\": \"PASS\" or \"REWRITE\",\n");
        reviewPrompt.append("  \"findings\": [\n");
        reviewPrompt.append("    {\"severity\": \"critical\"|\"warning\"|\"info\", \"message\": \"...\", \"suggestion\": \"...\"}\n");
        reviewPrompt.append("  ],\n");
        reviewPrompt.append("  \"summary\": \"Overall assessment\"\n");
        reviewPrompt.append("}\n");

        // Always request rewrittenPlan so we have the option
        reviewPrompt.append("\nInclude a 'rewrittenPlan' field in the response with your improved version of the plan if status is REWRITE.\n");

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 60,
                    "Review: calling LLM for analysis");
            webSocketHandler.sendLog(schemaId, "info", "Review: sending to LLM for analysis", node.getId());
        }

        String systemPrompt = "You are a structured reviewer. Analyze the provided plan and return findings in JSON format.";
        String analysisResult = llmService.streamingChat(model, systemPrompt, reviewPrompt.toString(), null,
                token -> {
                    if (webSocketHandler != null) {
                        webSocketHandler.sendToken(schemaId, node.getId(), token);
                    }
                });

        // Parse analysis result
        String reviewStatus = "PASS";
        String findingsText = "[]";
        String rewrittenPlan = null;
        String summary = "";

        if (analysisResult != null && !analysisResult.isBlank()) {
            try {
                String jsonStr = analysisResult.trim();
                int jsonStart = jsonStr.indexOf('{');
                int jsonEnd = jsonStr.lastIndexOf('}');
                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    jsonStr = jsonStr.substring(jsonStart, jsonEnd + 1);
                }
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(jsonStr);
                if (root.has("status")) {
                    reviewStatus = root.get("status").asText();
                }
                if (root.has("findings")) {
                    findingsText = root.get("findings").toString();
                }
                if (root.has("summary")) {
                    summary = root.get("summary").asText();
                }
                if (root.has("rewrittenPlan")) {
                    rewrittenPlan = root.get("rewrittenPlan").asText();
                }
            } catch (Exception e) {
                log.warn("Failed to parse review analysis JSON: {}", e.getMessage());
            }
        }

        // Build structured result
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", reviewStatus);
        resultMap.put("findings", findingsText);
        resultMap.put("summary", summary);
        resultMap.put("plan", planText);
        resultMap.put("originalInput", inputContent);
        resultMap.put("mode", mode);

        // ── Mode Handling ──
        boolean planWasRewritten = "REWRITE".equals(reviewStatus) && rewrittenPlan != null;
        String finalResult;

        switch (mode) {
            case "manual":
                // Always show human approval dialog
                if (planWasRewritten) {
                    // Store rewritten plan
                    resultMap.put("rewrittenPlan", rewrittenPlan);
                    resultMap.put("requiresApproval", true);

                    // Emit review_awaiting_approval WS event
                    if (webSocketHandler != null) {
                        Map<String, Object> approvalPayload = new HashMap<>();
                        approvalPayload.put("nodeId", node.getId());
                        approvalPayload.put("status", "AWAITING_APPROVAL");
                        approvalPayload.put("plan", planText);
                        approvalPayload.put("rewrittenPlan", rewrittenPlan);
                        approvalPayload.put("findings", findingsText);
                        approvalPayload.put("summary", summary);
                        approvalPayload.put("mode", "manual");
                        approvalPayload.put("iterationInfo", "Iteration 1 of unlimited");
                        webSocketHandler.sendLiveUpdate(schemaId, "review_awaiting_approval", approvalPayload);
                    }

                    // Set node to AWAITING_APPROVAL
                    node.setStatus(Node.NodeStatus.AWAITING_APPROVAL);

                    // Store result so downstream can pick up when approved
                    String resultJson = "{\"status\":\"AWAITING_APPROVAL\",\"findings\":" + findingsText + ",\"summary\":\"" + (summary != null ? summary.replace("\"", "\\\"") : "") + "\",\"plan\":\"" + (planText != null ? planText.replace("\"", "\\\"").replace("\n", "\\n") : "") + "\",\"rewrittenPlan\":\"" + (rewrittenPlan != null ? rewrittenPlan.replace("\"", "\\\"").replace("\n", "\\n") : "") + "\"}";
                    return resultJson;
                }
                // If PASS, no rewrite needed
                finalResult = "{\"status\":\"PASS\",\"findings\":" + findingsText + ",\"summary\":\"" + (summary != null ? summary.replace("\"", "\\\"") : "") + "\",\"plan\":\"" + (planText != null ? planText.replace("\"", "\\\"").replace("\n", "\\n") : "") + "\"}";
                break;

            case "auto":
                // Auto-rewrite up to maxAutoIterations
                int autoIteration = 0;
                String currentPlan = planText;
                while (autoIteration < maxAutoIterations && planWasRewritten) {
                    autoIteration++;
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schemaId, "info",
                                "Auto review iteration " + autoIteration + "/" + maxAutoIterations, node.getId());
                    }

                    // Re-run Phase 2 with rewritten plan
                    String reReviewPrompt = reviewPrompt.toString()
                            .replace(planText, rewrittenPlan != null ? rewrittenPlan : planText);
                    String reResult = llmService.streamingChat(model, systemPrompt, reReviewPrompt, null,
                            token -> {
                                if (webSocketHandler != null) {
                                    webSocketHandler.sendToken(schemaId, node.getId(), token);
                                }
                            });

                    // Parse re-result
                    if (reResult != null && !reResult.isBlank()) {
                        try {
                            String reJsonStr = reResult.trim();
                            int reJsonStart = reJsonStr.indexOf('{');
                            int reJsonEnd = reJsonStr.lastIndexOf('}');
                            if (reJsonStart >= 0 && reJsonEnd > reJsonStart) {
                                reJsonStr = reJsonStr.substring(reJsonStart, reJsonEnd + 1);
                            }
                            ObjectMapper mapper = new ObjectMapper();
                            JsonNode reRoot = mapper.readTree(reJsonStr);
                            String reStatus = reRoot.has("status") ? reRoot.get("status").asText() : "PASS";
                            if (reRoot.has("findings")) {
                                findingsText = reRoot.get("findings").toString();
                            }
                            if (reRoot.has("summary")) {
                                summary = reRoot.get("summary").asText();
                            }

                            if ("PASS".equals(reStatus)) {
                                // PASS before max → COMPLETED
                                resultMap.put("status", "PASS");
                                resultMap.put("plan", rewrittenPlan);
                                resultMap.put("rewriteIterations", autoIteration);
                                String passResult = "{\"status\":\"PASS\",\"findings\":" + findingsText + ",\"summary\":\"" + (summary != null ? summary.replace("\"", "\\\"") : "") + "\",\"plan\":\"" + (rewrittenPlan != null ? rewrittenPlan.replace("\"", "\\\"").replace("\n", "\\n") : "") + "\",\"rewriteIterations\":" + autoIteration + "}";
                                return passResult;
                            }

                            if (reRoot.has("rewrittenPlan")) {
                                rewrittenPlan = reRoot.get("rewrittenPlan").asText();
                                currentPlan = rewrittenPlan;
                            }
                        } catch (Exception e) {
                            log.warn("Failed to parse auto re-review: {}", e.getMessage());
                        }
                    }
                }

                // If max hit without PASS → FAILED
                if (planWasRewritten) {
                    node.setStatus(Node.NodeStatus.FAILED);
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schemaId, "error",
                                "Auto review failed after " + maxAutoIterations + " iterations", node.getId());
                    }
                    String failResult = "{\"status\":\"FAILED\",\"findings\":" + findingsText + ",\"summary\":\"Max auto iterations (" + maxAutoIterations + ") reached without PASS\",\"plan\":\"" + (currentPlan != null ? currentPlan.replace("\"", "\\\"").replace("\n", "\\n") : "") + "\",\"rewriteIterations\":" + autoIteration + "}";
                    return failResult;
                }

                finalResult = "{\"status\":\"PASS\",\"findings\":" + findingsText + ",\"summary\":\"" + (summary != null ? summary.replace("\"", "\\\"") : "") + "\",\"plan\":\"" + (currentPlan != null ? currentPlan.replace("\"", "\\\"").replace("\n", "\\n") : "") + "\"}";
                break;

            case "hybrid":
            default:
                // Auto-rewrite up to maxAutoIterations, then show human gate
                int hybridIteration = 0;
                String hybridPlan = planText;
                while (hybridIteration < maxAutoIterations && planWasRewritten) {
                    hybridIteration++;
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schemaId, "info",
                                "Hybrid review iteration " + hybridIteration + "/" + maxAutoIterations, node.getId());
                    }

                    String hyReReviewPrompt = reviewPrompt.toString()
                            .replace(planText, rewrittenPlan != null ? rewrittenPlan : planText);
                    String hyResult = llmService.streamingChat(model, systemPrompt, hyReReviewPrompt, null,
                            token -> {
                                if (webSocketHandler != null) {
                                    webSocketHandler.sendToken(schemaId, node.getId(), token);
                                }
                            });

                    if (hyResult != null && !hyResult.isBlank()) {
                        try {
                            String hyJsonStr = hyResult.trim();
                            int hyJsonStart = hyJsonStr.indexOf('{');
                            int hyJsonEnd = hyJsonStr.lastIndexOf('}');
                            if (hyJsonStart >= 0 && hyJsonEnd > hyJsonStart) {
                                hyJsonStr = hyJsonStr.substring(hyJsonStart, hyJsonEnd + 1);
                            }
                            ObjectMapper mapper = new ObjectMapper();
                            JsonNode hyRoot = mapper.readTree(hyJsonStr);
                            String hyStatus = hyRoot.has("status") ? hyRoot.get("status").asText() : "PASS";
                            if (hyRoot.has("findings")) {
                                findingsText = hyRoot.get("findings").toString();
                            }
                            if (hyRoot.has("summary")) {
                                summary = hyRoot.get("summary").asText();
                            }

                            if ("PASS".equals(hyStatus)) {
                                resultMap.put("status", "PASS");
                                resultMap.put("plan", rewrittenPlan);
                                String passResult = "{\"status\":\"PASS\",\"findings\":" + findingsText + ",\"summary\":\"" + (summary != null ? summary.replace("\"", "\\\"") : "") + "\",\"plan\":\"" + (rewrittenPlan != null ? rewrittenPlan.replace("\"", "\\\"").replace("\n", "\\n") : "") + "\"}";
                                return passResult;
                            }

                            if (hyRoot.has("rewrittenPlan")) {
                                rewrittenPlan = hyRoot.get("rewrittenPlan").asText();
                                hybridPlan = rewrittenPlan;
                            }
                        } catch (Exception e) {
                            log.warn("Failed to parse hybrid re-review: {}", e.getMessage());
                        }
                    }
                }

                // Max hit → show human gate dialog (same as manual)
                if (planWasRewritten || hybridIteration >= maxAutoIterations) {
                    resultMap.put("requiresApproval", true);
                    resultMap.put("rewrittenPlan", rewrittenPlan != null ? rewrittenPlan : hybridPlan);

                    if (webSocketHandler != null) {
                        Map<String, Object> approvalPayload = new HashMap<>();
                        approvalPayload.put("nodeId", node.getId());
                        approvalPayload.put("status", "AWAITING_APPROVAL");
                        approvalPayload.put("plan", hybridPlan);
                        approvalPayload.put("rewrittenPlan", rewrittenPlan);
                        approvalPayload.put("findings", findingsText);
                        approvalPayload.put("summary", summary);
                        approvalPayload.put("mode", "hybrid");
                        approvalPayload.put("iterationInfo", "Iteration " + hybridIteration + " of " + maxAutoIterations);
                        webSocketHandler.sendLiveUpdate(schemaId, "review_awaiting_approval", approvalPayload);
                    }

                    node.setStatus(Node.NodeStatus.AWAITING_APPROVAL);
                    return "{\"status\":\"AWAITING_APPROVAL\",\"findings\":" + findingsText + ",\"summary\":\"" + (summary != null ? summary.replace("\"", "\\\"") : "") + "\",\"plan\":\"" + (hybridPlan != null ? hybridPlan.replace("\"", "\\\"").replace("\n", "\\n") : "") + "\",\"rewrittenPlan\":\"" + (rewrittenPlan != null ? rewrittenPlan.replace("\"", "\\\"").replace("\n", "\\n") : "") + "\"}";
                }

                finalResult = "{\"status\":\"PASS\",\"findings\":" + findingsText + ",\"summary\":\"" + (summary != null ? summary.replace("\"", "\\\"") : "") + "\"}";
                break;
        }

        ObjectMapper finalMapper = new ObjectMapper();
        try {
            resultMap.put("finalResult", finalResult);
            return finalMapper.writeValueAsString(resultMap);
        } catch (Exception e) {
            log.warn("Failed to serialize review result: {}", e.getMessage());
            return finalResult;
        }
    }

    // ────────────────────────── Test accessors (package-private) ──────────────────────────

    String sanitizeCommandPublic(String command) { return sanitizeCommand(command); }
    void validateUrlPublic(String url) { validateUrl(url); }
    boolean isPathAllowedPublic(String path) { return isPathAllowed(path); }
    boolean evaluateConditionPublic(String expr, java.util.Map<String, Object> ctx) { return evaluateCondition(expr, ctx); }
    String interpolateVariablesPublic(String text, WorkflowSchema schema, java.util.Map<String, Object> preds) { return interpolateVariables(text, schema, preds); }
    String buildContextBlockPublic(java.util.Map<String, Object> preds) { return buildContextBlock(preds); }
    String writeOutputPublic(String outputType, String filePath, String fileFormat, String content) { return writeOutput(outputType, filePath, fileFormat, content); }
    boolean sleepWithCancelPublic(long millis, java.util.concurrent.atomic.AtomicBoolean cancelFlag) { return sleepWithCancel(millis, cancelFlag); }
    String executeOutputNodePublic(Node node, String schemaId, ExecutionMode mode) { return executeOutputNode(node, schemaId, mode); }
}
