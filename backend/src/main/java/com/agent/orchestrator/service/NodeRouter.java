package com.agent.orchestrator.service;

import com.agent.orchestrator.model.ExecutionMode;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.NodeExecution;
import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.LlmUsage;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Routes node execution to the appropriate handler based on node type.
 * Uses ExecutionUtilityService for shared helper methods.
 */
@Component
public class NodeRouter {

    private static final Logger log = LoggerFactory.getLogger(NodeRouter.class);

    private final ExecutionUtilityService utilityService;
    private final LlmService llmService;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final MemPalaceClient memPalaceClient;
    private final ToolExecutor toolExecutor;
    private final TransformService transformService;
    private final Neo4jSchemaRepository schemaRepository;
    private final PlanService planService;
    private final ProjectContextBuilder projectContextBuilder;
    private final ExecutionRepository executionRepository;
    private final ExecutionStateManager stateManager;
    private final ReasoningCapture reasoningCapture;
    private final List<NodeExecutionStrategy> strategies;
    private final AgentNodeStrategy agentStrategy;
    private final NodeOutputValidator outputValidator;
    private final MagicContextIndexer mcIndexer;
    private Map<String, NodeExecutionStrategy> strategyRegistry;
    private final Executor nodeExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public NodeRouter(ExecutionUtilityService utilityService,
                      LlmService llmService,
                      ExecutionWebSocketHandler webSocketHandler,
                      MemPalaceClient memPalaceClient,
                      ToolExecutor toolExecutor,
                      TransformService transformService,
                      Neo4jSchemaRepository schemaRepository,
                      PlanService planService,
                      ProjectContextBuilder projectContextBuilder,
                      ExecutionRepository executionRepository,
                      ExecutionStateManager stateManager,
                      ReasoningCapture reasoningCapture,
                       List<NodeExecutionStrategy> strategies,
                       AgentNodeStrategy agentStrategy,
                       NodeOutputValidator outputValidator,
                       MagicContextIndexer mcIndexer) {
        this.utilityService = utilityService;
        this.llmService = llmService;
        this.webSocketHandler = webSocketHandler;
        this.memPalaceClient = memPalaceClient;
        this.toolExecutor = toolExecutor;
        this.transformService = transformService;
        this.schemaRepository = schemaRepository;
        this.planService = planService;
        this.projectContextBuilder = projectContextBuilder;
        this.executionRepository = executionRepository;
        this.stateManager = stateManager;
        this.reasoningCapture = reasoningCapture;
        this.strategies = strategies;
        this.agentStrategy = agentStrategy;
        this.outputValidator = outputValidator;
        this.mcIndexer = mcIndexer;
    }

    @PostConstruct
    public void initStrategyRegistry() {
        Map<String, NodeExecutionStrategy> registry = new HashMap<>();
        for (NodeExecutionStrategy strategy : strategies) {
            registry.put(strategy.supportedNodeType(), strategy);
        }
        this.strategyRegistry = Collections.unmodifiableMap(registry);
    }

    public void executeNode(Node node, String schemaId, AtomicBoolean cancelFlag,
                            ExecutionMode mode, String resolvedModel) {
        String nodeExecutionId = null;
        try {
            if (cancelFlag.get()) {
                node.setStatus(Node.NodeStatus.FAILED);
                return;
            }

            node.setStatus(Node.NodeStatus.RUNNING);

            // Persist running status to DB if run created
            String runIdForStart = stateManager.getCurrentRunId(schemaId);
            if (runIdForStart != null) {
                try {
                    List<NodeExecution> execs = executionRepository.getNodeExecutionsByRun(runIdForStart);
                    NodeExecution ne = execs.stream()
                            .filter(exec -> exec.getNodeId().equals(node.getId()))
                            .findFirst().orElse(null);
                    if (ne != null) {
                        nodeExecutionId = ne.getId();
                        executionRepository.updateNodeExecution(
                                ne.getId(), "running", null, 0L, 0L, 0, null);
                    }
                } catch (Exception e) {
                    log.warn("Не удалось обновить статус узла в БД", e);
                }
            }

            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 10, "Начало выполнения");
                webSocketHandler.sendLog(schemaId, "info", "Начало выполнения узла [" + mode + "]", node.getId());
            }

            String result = "";
            String nodeType = node.getType();

            int autoRetry = getAutoRetryCount(node);
            int timeoutSecs = getTimeoutSeconds(node);

            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                for (int attempt = 1; attempt <= Math.max(1, autoRetry + 1); attempt++) {
                    try {
                        switch (nodeType) {
                            case "agent":
                                if (mode == ExecutionMode.DRY_RUN) {
                                    return agentStrategy.simulateAgentNode(node, schemaId);
                                } else if (mode == ExecutionMode.ANALYZE) {
                                    return agentStrategy.analyzeAgentNode(node, schemaId);
                                } else {
                                    return agentStrategy.executeToolAgentNode(node, schemaId, resolvedModel);
                                }

                            case "output":
                                return utilityService.executeOutputNode(node, schemaId, mode);

                            case "command":
                                return utilityService.executeCommandNode(node, schemaId);

                            case "filewrite":
                                return utilityService.executeFileWriteNode(node, schemaId);

                            case "source":
                                return utilityService.handleSourceNode(node, schemaId);

                            case "condition":
                                return handleConditionNode(node, schemaId);

                            case "transform":
                                return handleTransformNode(node, schemaId);

                            case "loop":
                                return handleLoopNode(node, schemaId, cancelFlag);

                            case "memory":
                                return handleMemoryNode(node, schemaId);

                            case "guardrail":
                                return handleGuardrailNode(node, schemaId);

                            case "human":
                                return handleHumanNode(node, schemaId, cancelFlag);

                            case "fallback":
                                return handleFallbackNode(node, schemaId);

                            case "subagent":
                                return utilityService.executeSubagentNode(node, schemaId, cancelFlag, mode);

                            default:
                                NodeExecutionStrategy strategy = strategyRegistry.get(nodeType);
                                if (strategy != null) {
                                    Map<String, Object> strategyResult = strategy.executeNode(node, null, null, null, null,
                                            Map.of("model", resolvedModel), schemaId);
                                    NodeOutputValidator.ValidationResult vr = outputValidator.validate(nodeType, strategyResult, node);
                                    if (!vr.isValid() && webSocketHandler != null) {
                                        webSocketHandler.sendLog(schemaId, "warning",
                                                "Output validation: " + String.join("; ", vr.getIssues()), node.getId());
                                    }
                                    return (String) strategyResult.getOrDefault("result", "");
                                }
                                log.warn("Unknown node type: {}", nodeType);
                                return "Неизвестный тип узла: " + nodeType;
                        }
                    } catch (Exception execEx) {
                        if (attempt <= autoRetry && isTransientError(execEx)) {
                            int waitMs = 5000 * attempt;
                            log.warn("Transient error on attempt {}/{} for node {}: {}. Retrying in {}ms",
                                    attempt, autoRetry, node.getId(), execEx.getMessage(), waitMs);
                            if (webSocketHandler != null) {
                                webSocketHandler.sendLog(schemaId, "warning",
                                        "Retry " + attempt + "/" + autoRetry + " after: " + execEx.getMessage(), node.getId());
                            }
                            try { Thread.sleep(waitMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                            if (Thread.currentThread().isInterrupted()) {
                                log.warn("Node execution interrupted during retry wait");
                                break;
                            }
                            if (cancelFlag.get()) {
                                log.warn("Node execution cancelled during retry wait");
                                break;
                            }
                        } else {
                            throw new RuntimeException(execEx);
                        }
                    }
                }
                return "";
            }, nodeExecutor);
            try {
                result = future.get(timeoutSecs, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                future.cancel(true);
                String msg = "Node execution timed out after " + timeoutSecs + "s";
                log.error("Timeout ({}s) executing node {}: {}", timeoutSecs, node.getId(), node.getName());
                node.setStatus(Node.NodeStatus.FAILED);
            if (webSocketHandler != null) {
                webSocketHandler.sendError(schemaId, node.getId(), msg,
                        com.agent.orchestrator.websocket.ExecutionWebSocketHandler.ErrorCategory.TIMEOUT);
                webSocketHandler.sendLog(schemaId, "error",
                        "Timeout: " + node.getName() + " exceeded " + timeoutSecs + "s", node.getId(),
                        com.agent.orchestrator.websocket.ExecutionWebSocketHandler.ErrorCategory.TIMEOUT);
            }
                if (nodeExecutionId != null) {
                    try {
                        executionRepository.updateNodeExecution(
                                nodeExecutionId, "failed", null, 0L, 0L, 0, "Timeout: " + timeoutSecs + "s");
                    } catch (Exception ex) {
                        log.warn("Failed to persist timeout error", ex);
                    }
                }
                return;
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                }
                throw new RuntimeException(cause);
            }

            // Post-dispatch
            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 90, "Формирование результата");
                webSocketHandler.sendLog(schemaId, "info", "Формирование результата", node.getId());
            }
            if (webSocketHandler != null) {
                webSocketHandler.sendResult(schemaId, node.getId(), result);
            }
            stateManager.getNodeResults().computeIfAbsent(schemaId, k -> new ConcurrentHashMap<>())
                    .put(node.getId(), result);
            if (node.getData() != null) {
                node.getData().setResult(result);
            }

            // Index result in Magic Context for future RAG retrieval (all node types)
            if (result != null && !result.isBlank() && mcIndexer.isAvailable()) {
                mcIndexer.indexNodeOutput(schemaId, node.getId(), nodeType, result,
                        schemaRepository.findById(schemaId) != null
                                ? schemaRepository.findById(schemaId).getName() : "",
                        node.getName() != null ? node.getName() : "");
            }

            // Don't override review nodes waiting for human approval
            boolean isAwaitingApproval = node.getStatus() == Node.NodeStatus.AWAITING_APPROVAL;
            if (!isAwaitingApproval) {
                if (webSocketHandler != null) {
                    webSocketHandler.sendProgress(schemaId, node.getId(), "COMPLETED", 100, "Завершено");
                    webSocketHandler.sendLog(schemaId, "success", "Узел успешно выполнен", node.getId());
                }
                node.setStatus(Node.NodeStatus.COMPLETED);
            }

            // expectedToolCall verification: fail if expected tool was not called
            String expectedTool = getExpectedToolCall(node);
            if (expectedTool != null && result != null) {
                int actualCalls = estimateToolCalls(result);
                if (actualCalls == 0) {
                    String errMsg = "Expected tool call \"" + expectedTool
                        + "\" but agent returned only text (no tool calls)";
                    log.error("Node {}: {}", node.getId(), errMsg);
                    node.setStatus(Node.NodeStatus.FAILED);
                    if (webSocketHandler != null) {
                        webSocketHandler.sendError(schemaId, node.getId(), errMsg,
                                ExecutionWebSocketHandler.ErrorCategory.VALIDATION_ERROR);
                    }
                    return; // stop processing, don't persist as completed
                }
            }

            // Persist result to Neo4j with token/tool call + reasoning tracking
            if (nodeExecutionId != null) {
                try {
                    LlmUsage usage = stateManager.getAndClearTokenUsage(schemaId, node.getId());
                    long tokensUsed = usage != null ? usage.getTotalTokens() : 0L;
                    int toolCalls = 0;
                    if (result != null) {
                        toolCalls = estimateToolCalls(result);
                    }
                    String reasoning = reasoningCapture.consume(node.getId());
                    executionRepository.updateNodeExecution(
                            nodeExecutionId, "completed", result, tokensUsed, 0L, toolCalls, null, reasoning);
                } catch (Exception e) {
                    log.warn("Не удалось сохранить результат узла в БД", e);
                }
            }

        } catch (Exception e) {
            log.error("Ошибка выполнения узла {}: {}", node.getId(), e.getMessage(), e);
            node.setStatus(Node.NodeStatus.FAILED);
            if (webSocketHandler != null) {
                webSocketHandler.sendError(schemaId, node.getId(), e.getMessage(),
                        com.agent.orchestrator.websocket.ExecutionWebSocketHandler.ErrorCategory.fromException(e));
            }
            // Persist error to Neo4j
            if (nodeExecutionId != null) {
                try {
                    LlmUsage usage = stateManager.getAndClearTokenUsage(schemaId, node.getId());
                    long tokensUsed = usage != null ? usage.getTotalTokens() : 0L;
                    executionRepository.updateNodeExecution(
                            nodeExecutionId, "failed", null, tokensUsed, 0L, 0, e.getMessage());
                } catch (Exception ex) {
                    log.warn("Не удалось сохранить ошибку узла в БД", ex);
                }
            }
        }
    }

    // ─── Inline handlers (simple enough to keep here) ───

    /**
     * Estimate tool call count from agent result text.
     * Counts occurrences of "tool_calls" JSON keys or named tool invocations.
     */
    /**
     * Read expectedToolCall from node config (optional).
     * If set and the agent returns no tool calls, the node will fail.
     */
    private String getExpectedToolCall(Node node) {
        if (node.getData() == null || node.getData().getConfig() == null) return null;
        Object val = node.getData().getConfig().get("expectedToolCall");
        return val instanceof String && !((String) val).isBlank() ? (String) val : null;
    }

    /**
     * Read autoRetryCount from node config (default 0 — no automatic retry).
     */
    int getAutoRetryCount(Node node) {
        if (node.getData() == null || node.getData().getConfig() == null) return 0;
        Object val = node.getData().getConfig().get("autoRetryCount");
        if (val instanceof Number) {
            int n = ((Number) val).intValue();
            return Math.max(0, Math.min(n, 5)); // cap at 5
        }
        return 0;
    }

    /**
     * Read timeoutSeconds from node config (default 600 for agent/code-gen nodes).
     * Priority: node.data.timeoutSeconds > node.data.config.timeoutSeconds > 600
     */
    int getTimeoutSeconds(Node node) {
        if (node.getData() == null) return 600;
        if (node.getData().getTimeoutSeconds() != null) {
            return Math.max(1, Math.min(3600, node.getData().getTimeoutSeconds()));
        }
        if (node.getData().getConfig() != null) {
            Object val = node.getData().getConfig().get("timeoutSeconds");
            if (val instanceof Number) {
                return Math.max(1, Math.min(3600, ((Number) val).intValue()));
            }
        }
        return 600;
    }

    /**
     * Detect transient errors that are safe to retry: HTTP 429/502/503, socket timeouts,
     * and common rate-limit / temporary-unavailability message patterns.
     */
    boolean isTransientError(Exception e) {
        if (e == null) return false;
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.isEmpty()) {
            // Check cause for wrapped exceptions
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                return isTransientError((Exception) cause);
            }
            return false;
        }

        // HTTP status codes
        if (msg.contains("429") || msg.contains("502") || msg.contains("503")) return true;

        // Timeout errors
        if (e instanceof SocketTimeoutException) return true;
        if (msg.contains("timeout") || msg.contains("timed out")) return true;

        // Common transient message patterns
        if (msg.contains("rate limit") || msg.contains("rate_limit")
                || msg.contains("too many requests")
                || msg.contains("service unavailable")
                || msg.contains("temporarily")
                || msg.contains("try again later")
                || msg.contains("server error")
                || msg.contains("internal server error")
                // Empty response from LLM — retryable (rate limit can produce empty choices)
                || msg.contains("no choices")
                // Connection resets from network issues
                || msg.contains("connection reset")
                || msg.contains("connection refused")) return true;

        // Check cause for wrapped exceptions (message didn't match)
        Throwable cause = e.getCause();
        if (cause instanceof Exception && isTransientError((Exception) cause)) return true;

        return false;
    }

    private int estimateToolCalls(String result) {
        if (result == null || result.isBlank()) return 0;
        int count = 0;
        // Count "tool_calls" keys in LLM response
        int idx = 0;
        while ((idx = result.indexOf("\"tool_calls\"", idx)) != -1) {
            count++;
            idx += 12;
        }
        // Also count named tool calls like "file_write", "bash", etc.
        String[] toolNames = {"file_write", "bash", "file_read", "grep", "glob", "web_fetch", "read", "write", "directory_read"};
        for (String name : toolNames) {
            int nameIdx = 0;
            while ((nameIdx = result.indexOf("\"" + name + "\"", nameIdx)) != -1) {
                count++;
                nameIdx += name.length() + 2;
            }
        }
        return count;
    }

    private String handleConditionNode(Node node, String schemaId) {
        String conditionExpr = node.getData() != null && node.getData().getCondition() != null
                ? node.getData().getCondition() : "true";

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "Вычисление условия");
            webSocketHandler.sendLog(schemaId, "info", "Вычисление условия: " + conditionExpr, node.getId());
        }

        Map<String, Object> context = utilityService.collectPredecessorResults(
                schemaRepository.findById(schemaId), node.getId());
        boolean conditionResult = utilityService.evaluateCondition(conditionExpr, context);
        String result = String.valueOf(conditionResult);
        stateManager.getConditionResults().put(schemaId + ":" + node.getId(), result);

        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "info",
                    "Условие '" + conditionExpr + "' = " + conditionResult, node.getId());
        }
        return result;
    }

    private String handleTransformNode(Node node, String schemaId) {
        log.info("Transform node {} starting", node.getId());
        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 30, "Применение трансформаций");
        }

        var predResults = utilityService.collectPredecessorResults(
                schemaRepository.findById(schemaId), node.getId());
        String input = null;
        if (!predResults.isEmpty()) {
            Object firstValue = predResults.values().iterator().next();
            input = firstValue != null ? firstValue.toString() : null;
        }

        var transforms = node.getData() != null ? node.getData().getTransforms() : null;
        String transformed = transformService.applyTransforms(input, transforms);

        var routes = node.getData() != null ? node.getData().getRoutes() : null;
        String matchedPort = null;
        String routeResult = null;
        String result;

        if (routes != null && !routes.isEmpty()) {
            for (var route : routes) {
                String evaluated = transformService.evaluateRoute(transformed, route);
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

        stateManager.getConditionResults().put(schemaId + ":" + node.getId(), matchedPort);
        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "info",
                    "Transform applied, route: " + matchedPort, node.getId());
        }
        return result;
    }

    private String handleLoopNode(Node node, String schemaId, AtomicBoolean cancelFlag) {
        String loopCond = node.getData() != null && node.getData().getLoopCondition() != null
                ? node.getData().getLoopCondition() : "iterations < 10";
        int maxIter = node.getData() != null ? node.getData().getMaxIterations() : 10;
        if (maxIter <= 0) maxIter = 10;

        // Fetch schema once before loop instead of per iteration
        var schema = schemaRepository.findById(schemaId);

        int iterations = 0;
        boolean shouldContinue = true;

        while (shouldContinue && iterations < maxIter && !cancelFlag.get()) {
            iterations++;
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("iterations", iterations);
            ctx.put("maxIterations", maxIter);
            ctx.putAll(utilityService.collectPredecessorResults(schema, node.getId()));

            if (webSocketHandler != null) {
                int pct = (int) ((iterations / (double) maxIter) * 90);
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", pct,
                        "Итерация " + iterations + "/" + maxIter);
            }

            shouldContinue = utilityService.evaluateCondition(loopCond, ctx);
            if (cancelFlag.get()) return "Отменено";
        }

        return "Завершено за " + iterations + " итераций";
    }

    private String handleMemoryNode(Node node, String schemaId) {
        String searchQuery = node.getData() != null && node.getData().getSourceData() != null
                ? node.getData().getSourceData() : node.getName();
        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "Поиск в памяти");
            webSocketHandler.sendLog(schemaId, "info", "Поиск в памяти: " + searchQuery, node.getId());
        }
        if (memPalaceClient.isEnabled()) {
            var memResults = memPalaceClient.search(searchQuery, null, null, 5);
            if (memResults.isEmpty()) {
                return "Ничего не найдено по запросу: " + searchQuery;
            } else {
                StringBuilder sb = new StringBuilder();
                for (var r : memResults) sb.append("- ").append(r.get("content")).append("\n");
                return sb.toString().trim();
            }
        } else {
            return "Память недоступна (MemPalace не подключен)";
        }
    }

    private String handleGuardrailNode(Node node, String schemaId) {
        var predResults = utilityService.collectPredecessorResults(
                schemaRepository.findById(schemaId), node.getId());
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
            String validationPrompt = "Проверь, соответствует ли следующий текст правилам.\n"
                    + "Правила:\n" + rules + "\n\nТекст:\n" + input
                    + "\n\nОтветь только 'ДА' или 'НЕТ: [причина]'";
            return llmService.chat(null, null, validationPrompt, null).text();
        } else if ("transform".equals(guardrailMode)) {
            String transformPrompt = "Примени следующие правила трансформации к тексту.\n"
                    + "Правила:\n" + rules + "\n\nТекст:\n" + input;
            return llmService.chat(null, null, transformPrompt, null).text();
        } else {
            return input;
        }
    }

    private String handleHumanNode(Node node, String schemaId, AtomicBoolean cancelFlag) {
        var predResults = utilityService.collectPredecessorResults(
                schemaRepository.findById(schemaId), node.getId());
        String input = predResults.values().stream().findFirst().map(Object::toString).orElse("");
        String question = node.getData() != null && node.getData().getUserPrompt() != null
                ? node.getData().getUserPrompt() : "Подтвердите результат";

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "Ожидание подтверждения");
            webSocketHandler.sendLog(schemaId, "warning", "Требуется подтверждение: " + question, node.getId());
        }
        if (!utilityService.sleepWithCancel(1000, cancelFlag)) return "Отменено";
        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "success", "Авто-подтверждение", node.getId());
        }
        return "Подтверждено: " + input;
    }

    private String handleFallbackNode(Node node, String schemaId) {
        WorkflowSchema fallbackSchema = schemaRepository.findById(schemaId);
        var predResults = utilityService.collectPredecessorResults(fallbackSchema, node.getId());
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
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "warning",
                        "Fallback активирован: " + node.getName(), node.getId());
            }
            return llmService.chat(null, null, fallbackPrompt, null).text();
        } else {
            return "Пропущен (предшественник успешен)";
        }
    }
}
