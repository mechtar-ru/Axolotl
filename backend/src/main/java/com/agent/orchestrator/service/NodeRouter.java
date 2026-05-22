package com.agent.orchestrator.service;

import com.agent.orchestrator.model.ExecutionMode;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.NodeExecution;
import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Routes node execution to the appropriate handler based on node type.
 * Uses ExecutionUtilityService for shared helper methods.
 */
@Component
public class NodeRouter {

    private static final Logger log = LoggerFactory.getLogger(NodeRouter.class);

    private final NodeExecutor nodeExecutor;
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

    public NodeRouter(NodeExecutor nodeExecutor,
                      ExecutionUtilityService utilityService,
                      LlmService llmService,
                      ExecutionWebSocketHandler webSocketHandler,
                      MemPalaceClient memPalaceClient,
                      ToolExecutor toolExecutor,
                      TransformService transformService,
                      Neo4jSchemaRepository schemaRepository,
                      PlanService planService,
                      ProjectContextBuilder projectContextBuilder,
                      ExecutionRepository executionRepository,
                      ExecutionStateManager stateManager) {
        this.nodeExecutor = nodeExecutor;
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
                    log.debug("Не удалось обновить статус узла в БД: {}", e.getMessage());
                }
            }

            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 10, "Начало выполнения");
                webSocketHandler.sendLog(schemaId, "info", "Начало выполнения узла [" + mode + "]", node.getId());
            }

            String result = "";
            String nodeType = node.getType();

            switch (nodeType) {
                case "agent":
                    if (mode == ExecutionMode.DRY_RUN) {
                        result = nodeExecutor.simulateAgentNode(node, schemaId);
                    } else if (mode == ExecutionMode.ANALYZE) {
                        result = nodeExecutor.analyzeAgentNode(node, schemaId);
                    } else {
                        result = nodeExecutor.executeAgentNode(node, schemaId, resolvedModel);
                    }
                    break;

                case "output":
                    result = utilityService.executeOutputNode(node, schemaId, mode);
                    break;

                case "command":
                    result = utilityService.executeCommandNode(node, schemaId);
                    break;

                case "filewrite":
                    result = utilityService.executeFileWriteNode(node, schemaId);
                    break;

                case "source":
                    result = utilityService.handleSourceNode(node, schemaId);
                    break;

                case "condition":
                    result = handleConditionNode(node, schemaId);
                    break;

                case "transform":
                    result = handleTransformNode(node, schemaId);
                    break;

                case "loop":
                    result = handleLoopNode(node, schemaId, cancelFlag);
                    break;

                case "memory":
                    result = handleMemoryNode(node, schemaId);
                    break;

                case "guardrail":
                    result = handleGuardrailNode(node, schemaId);
                    break;

                case "verifier":
                    result = nodeExecutor.executeVerifierNode(node, schemaId, resolvedModel);
                    break;

                case "review":
                    result = nodeExecutor.executeReviewNode(node, schemaId, resolvedModel);
                    break;

                case "human":
                    result = handleHumanNode(node, schemaId, cancelFlag);
                    break;

                case "fallback":
                    result = handleFallbackNode(node, schemaId);
                    break;

                case "subagent":
                    result = utilityService.executeSubagentNode(node, schemaId, cancelFlag, mode);
                    break;

                case "schemabuilder":
                    result = nodeExecutor.executeSchemaBuilderNode(node, schemaId, resolvedModel);
                    break;

                default:
                    result = "Неизвестный тип узла: " + nodeType;
                    log.warn("Unknown node type: {}", nodeType);
                    break;
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
            // Don't override review nodes waiting for human approval
            boolean isAwaitingApproval = node.getStatus() == Node.NodeStatus.AWAITING_APPROVAL;
            if (!isAwaitingApproval) {
                if (webSocketHandler != null) {
                    webSocketHandler.sendProgress(schemaId, node.getId(), "COMPLETED", 100, "Завершено");
                    webSocketHandler.sendLog(schemaId, "success", "Узел успешно выполнен", node.getId());
                }
                node.setStatus(Node.NodeStatus.COMPLETED);
            }

            // Persist result to Neo4j
            if (nodeExecutionId != null) {
                try {
                    executionRepository.updateNodeExecution(
                            nodeExecutionId, "completed", result, 0L, 0L, 0, null);
                } catch (Exception e) {
                    log.debug("Не удалось сохранить результат узла в БД: {}", e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("Ошибка выполнения узла {}: {}", node.getId(), e.getMessage(), e);
            node.setStatus(Node.NodeStatus.FAILED);
            if (webSocketHandler != null) {
                webSocketHandler.sendError(schemaId, node.getId(), e.getMessage());
            }
            // Persist error to Neo4j
            if (nodeExecutionId != null) {
                try {
                    executionRepository.updateNodeExecution(
                            nodeExecutionId, "failed", null, 0L, 0L, 0, e.getMessage());
                } catch (Exception ex) {
                    log.debug("Не удалось сохранить ошибку узла в БД: {}", ex.getMessage());
                }
            }
        }
    }

    // ─── Inline handlers (simple enough to keep here) ───

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
            return llmService.chat(null, null, validationPrompt, null);
        } else if ("transform".equals(guardrailMode)) {
            String transformPrompt = "Примени следующие правила трансформации к тексту.\n"
                    + "Правила:\n" + rules + "\n\nТекст:\n" + input;
            return llmService.chat(null, null, transformPrompt, null);
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
            return llmService.chat(null, null, fallbackPrompt, null);
        } else {
            return "Пропущен (предшественник успешен)";
        }
    }
}
