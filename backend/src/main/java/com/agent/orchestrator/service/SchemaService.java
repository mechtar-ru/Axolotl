package com.agent.orchestrator.service;

import com.agent.orchestrator.model.*;
import com.agent.orchestrator.repository.SchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.springframework.stereotype.Service;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class SchemaService {

    private final SchemaRepository schemaRepository;
    private final LlmService llmService;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final Map<String, CompletableFuture<?>> runningExecutions = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final Map<String, String> conditionResults = new ConcurrentHashMap<>();
    private final List<ExecutionRecord> executionHistory = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_HISTORY = 100;

    public SchemaService(SchemaRepository schemaRepository,
            LlmService llmService,
            ExecutionWebSocketHandler webSocketHandler) {
        this.schemaRepository = schemaRepository;
        this.llmService = llmService;
        this.webSocketHandler = webSocketHandler;
        initDemoSchema();
    }

    public List<WorkflowSchema> getAllSchemas() {
        return schemaRepository.findAll();
    }

    public WorkflowSchema getSchema(String id) {
        return schemaRepository.findById(id);
    }

    public WorkflowSchema createSchema(WorkflowSchema schema) {
        String id = UUID.randomUUID().toString();
        schema.setId(id);
        schema.setCreatedAt(Instant.now().toString());
        schema.setUpdatedAt(Instant.now().toString());
        schemaRepository.save(schema);
        System.out.println("✅ Создана схема: " + schema.getName() + " (ID: " + id + ")");
        return schema;
    }

    public WorkflowSchema updateSchema(String id, WorkflowSchema schema) {
        schema.setId(id);
        schema.setUpdatedAt(Instant.now().toString());
        schemaRepository.save(schema);
        System.out.println("✅ Обновлена схема: " + schema.getName() + " (ID: " + id + ")");
        System.out.println("   - Узлов: " + (schema.getNodes() != null ? schema.getNodes().size() : 0));
        System.out.println("   - Связей: " + (schema.getEdges() != null ? schema.getEdges().size() : 0));
        return schema;
    }

    public void deleteSchema(String id) {
        schemaRepository.delete(id);
        System.out.println("🗑 Удалена схема: " + id);
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

    public void executeSchema(String id) {
        WorkflowSchema schema = schemaRepository.findById(id);
        if (schema == null)
            return;
        if (runningExecutions.containsKey(id)) {
            System.out.println("⚠️ Схема уже выполняется: " + id);
            return;
        }

        if (schema.getNodes() != null) {
            for (Node node : schema.getNodes()) {
                node.setStatus(Node.NodeStatus.IDLE);
            }
        }

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        cancelFlags.put(id, cancelFlag);
        CompletableFuture<?> future = CompletableFuture.runAsync(() -> executeWorkflow(schema, cancelFlag));
        runningExecutions.put(id, future);
        future.whenComplete((result, ex) -> {
            runningExecutions.remove(id);
            cancelFlags.remove(id);
            if (ex != null && !(ex instanceof CancellationException)) {
                System.err.println("❌ Ошибка выполнения схемы " + id + ": " + ex.getMessage());
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
        System.out.println("🛑 Остановка выполнения схемы запрошена: " + id);
    }

    public List<ExecutionRecord> getExecutionHistory(String schemaId) {
        return executionHistory.stream()
                .filter(r -> schemaId.equals(r.getSchemaId()))
                .sorted((a, b) -> Long.compare(b.getStartTime(), a.getStartTime()))
                .limit(50)
                .collect(Collectors.toList());
    }

    public List<ExecutionRecord> getAllExecutionHistory() {
        return executionHistory.stream()
                .sorted((a, b) -> Long.compare(b.getStartTime(), a.getStartTime()))
                .limit(50)
                .collect(Collectors.toList());
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

    private void executeWorkflow(WorkflowSchema schema, AtomicBoolean cancelFlag) {
        System.out.println("▶️ Выполнение схемы: " + schema.getName());
        long startTime = System.currentTimeMillis();
        long workflowStartTime = startTime;

        conditionResults.keySet().removeIf(k -> k.startsWith(schema.getId() + ":"));

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schema.getId(), "system", "STARTED", 0, "Выполнение начато");
            webSocketHandler.sendLog(schema.getId(), "info", "Выполнение схемы начато: " + schema.getName(), null);
        }

        // Получить уровни выполнения (узлы на одном уровне можно запускать параллельно)
        List<List<Node>> levels = getExecutionLevels(schema);
        Set<String> skippedNodes = computeSkippedNodes(schema);

        int totalNodes = levels.stream().mapToInt(List::size).sum();
        int completedCount = 0;

        for (List<Node> level : levels) {
            if (cancelFlag.get()) break;

            List<Node> executable = level.stream()
                    .filter(node -> !skippedNodes.contains(node.getId()))
                    .filter(node -> !cancelFlag.get())
                    .toList();

            // Логирование пропущенных узлов
            for (Node node : level) {
                if (skippedNodes.contains(node.getId())) {
                    System.out.println("⏭ Пропуск узла (невыполненная ветка условия): " + node.getId());
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schema.getId(), "info",
                                "Пропуск узла (невыполненная ветка): " + node.getName(), node.getId());
                    }
                }
            }

            if (executable.isEmpty()) continue;

            // Параллельное выполнение узлов одного уровня
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Node node : executable) {
                System.out.println("⏸ Начало выполнения узла: " + node.getId() + " (" + node.getName() + ")");
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schema.getId(), "info",
                            "Начало выполнения узла: " + node.getName(), node.getId());
                }

                final long nodeStartTime = System.currentTimeMillis();
                futures.add(CompletableFuture.runAsync(() -> {
                    executeNode(node, schema.getId(), cancelFlag);
                    long nodeTime = System.currentTimeMillis() - nodeStartTime;
                    System.out.println("✅ Узел завершен: " + node.getId() + " (" + node.getName() + ") - " + nodeTime + "мс");
                    if (webSocketHandler != null) {
                        webSocketHandler.sendNodeTime(schema.getId(), node.getId(), nodeTime);
                    }
                }));
            }

            // Дождаться завершения всех узлов уровня
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } catch (Exception e) {
                System.err.println("❌ Ошибка при параллельном выполнении уровня: " + e.getMessage());
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
            System.out.println("⚠️ Выполнение схемы отменено: " + schema.getName());
            recordExecution(schema, workflowStartTime, totalTime, totalNodes, nodesCompleted, "cancelled");
        } else {
            if (webSocketHandler != null) {
                webSocketHandler.sendComplete(schema.getId(), totalTime, nodesCompleted);
                double finalNodesPerSecond = totalTime > 0 ? (double) nodesCompleted / (totalTime / 1000.0) : 0;
                webSocketHandler.sendMetrics(schema.getId(), nodesCompleted, nodesCompleted, totalTime, finalNodesPerSecond);
                webSocketHandler.sendLog(schema.getId(), "success",
                        "Выполнение схемы завершено: " + totalTime + "мс, узлов: " + nodesCompleted, null);
            }
            System.out.println("✅ Выполнение схемы завершено: " + schema.getName() +
                    " (" + totalTime + "мс, " + nodesCompleted + "/" + totalNodes + " узлов)");
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
                System.out.println("⚠️ Обнаружены циклические зависимости!");
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

        System.out.println("📊 Уровни выполнения:");
        for (int i = 0; i < levels.size(); i++) {
            System.out.println("  Уровень " + i + ": " +
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
            Value bindings = ctx.getBindings("js");
            context.forEach(bindings::putMember);
            Value result = ctx.eval("js", "Boolean(" + expression + ")");
            return result.asBoolean();
        } catch (Exception e) {
            System.err.println("Ошибка вычисления условия '" + expression + "': " + e.getMessage());
            return false;
        }
    }

    private java.util.Map<String, Object> collectPredecessorResults(WorkflowSchema schema, String nodeId) {
        java.util.Map<String, Object> results = new java.util.HashMap<>();
        if (schema.getEdges() == null || schema.getNodes() == null) {
            return results;
        }
        for (Edge edge : schema.getEdges()) {
            if (nodeId.equals(edge.getTarget())) {
                String sourceId = edge.getSource();
                for (Node n : schema.getNodes()) {
                    if (sourceId.equals(n.getId()) && n.getData() != null && n.getData().getResult() != null) {
                        results.put(n.getName().replaceAll("\\s+", "_"), n.getData().getResult());
                    }
                }
            }
        }
        return results;
    }

    /**
     * Формирует текстовый блок контекста из результатов предшествующих узлов.
     */
    private String buildContextBlock(Map<String, Object> predecessorResults) {
        if (predecessorResults.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        predecessorResults.forEach((name, value) -> {
            sb.append("[").append(name).append("]: ").append(value).append("\n");
        });
        return sb.toString().trim();
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

    private void executeNode(Node node, String schemaId, AtomicBoolean cancelFlag) {
        try {
            if (cancelFlag.get()) {
                node.setStatus(Node.NodeStatus.FAILED);
                return;
            }

            node.setStatus(Node.NodeStatus.RUNNING);

            // 0%: Начало выполнения
            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 0, "Начало выполнения");
                webSocketHandler.sendLog(schemaId, "info", "Начало выполнения узла", node.getId());
            }
            if (!sleepWithCancel(200, cancelFlag))
                return;

            // 30%: Подготовка данных
            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 30, "Подготовка данных");
                webSocketHandler.sendLog(schemaId, "info", "Подготовка данных", node.getId());
            }
            if (!sleepWithCancel(300, cancelFlag))
                return;

            String result = "";

            if ("agent".equals(node.getType())) {
                // 50%: Отправка запроса к LLM
                if (webSocketHandler != null) {
                    webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "Отправка запроса к AI");
                    webSocketHandler.sendLog(schemaId, "info", "Отправка запроса к LLM", node.getId());
                }

                String prompt = node.getData() != null && node.getData().getUserPrompt() != null
                        ? node.getData().getUserPrompt()
                        : "Анализируй данные";
                String model = node.getData() != null ? node.getData().getModel() : null;
                String systemPrompt = node.getData() != null ? node.getData().getSystemPrompt() : null;

                // Подставить результаты предшественников в промпт
                WorkflowSchema currentSchema = schemaRepository.findById(schemaId);
                Map<String, Object> predecessorResults = collectPredecessorResults(currentSchema, node.getId());
                String contextBlock = buildContextBlock(predecessorResults);
                if (!contextBlock.isEmpty()) {
                    String effectiveSystem = (systemPrompt != null ? systemPrompt + "\n\n" : "") +
                            "Контекст от предыдущих узлов:\n" + contextBlock;
                    systemPrompt = effectiveSystem;
                }

                // Variable interpolation: {{input}}, {{prev_result}}, {{node:Name}}
                prompt = interpolateVariables(prompt, currentSchema, predecessorResults);
                if (systemPrompt != null) {
                    systemPrompt = interpolateVariables(systemPrompt, currentSchema, predecessorResults);
                }

                result = llmService.chat(model, systemPrompt, prompt, null);

                if (webSocketHandler != null) {
                    webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 70, "Ответ получен");
                    webSocketHandler.sendLog(schemaId, "info", "Ответ от LLM получен", node.getId());
                }

            } else if ("source".equals(node.getType())) {
                // Используем реальные данные из SourceNode или дефолт
                if (node.getData() != null && node.getData().getSourceData() != null && !node.getData().getSourceData().isEmpty()) {
                    result = node.getData().getSourceData();
                } else {
                    result = "Данные из источника: " + node.getName();
                }
                if (!sleepWithCancel(300, cancelFlag))
                    return;

            } else if ("condition".equals(node.getType())) {
                String conditionExpr = node.getData() != null && node.getData().getCondition() != null
                        ? node.getData().getCondition()
                        : "true";

                if (webSocketHandler != null) {
                    webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "Вычисление условия");
                    webSocketHandler.sendLog(schemaId, "info", "Вычисление условия: " + conditionExpr, node.getId());
                }
                if (!sleepWithCancel(200, cancelFlag))
                    return;

                java.util.Map<String, Object> context = collectPredecessorResults(
                        schemaRepository.findById(schemaId), node.getId());
                boolean conditionResult = evaluateCondition(conditionExpr, context);
                result = String.valueOf(conditionResult);
                conditionResults.put(schemaId + ":" + node.getId(), result);

                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schemaId, "info",
                            "Условие '" + conditionExpr + "' = " + conditionResult, node.getId());
                }
                if (!sleepWithCancel(200, cancelFlag))
                    return;

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
                    if (!sleepWithCancel(200, cancelFlag)) return;
                }

                result = "Завершено за " + iterations + " итераций";

            } else if ("output".equals(node.getType())) {
                result = "Результат сохранен: " + node.getName();
                if (!sleepWithCancel(200, cancelFlag))
                    return;
            }

            // 90%: Формирование результата
            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 90, "Формирование результата");
                webSocketHandler.sendLog(schemaId, "info", "Формирование результата", node.getId());
            }
            if (!sleepWithCancel(200, cancelFlag))
                return;

            // Отправка результата
            if (webSocketHandler != null) {
                webSocketHandler.sendResult(schemaId, node.getId(), result);
            }

            // 100%: Завершено
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
                webSocketHandler.sendError(schemaId, node.getId(), "Ошибка выполнения: " + e.getMessage());
                webSocketHandler.sendLog(schemaId, "error", "Ошибка выполнения: " + e.getMessage(), node.getId());
            }
            System.err.println("❌ Ошибка выполнения узла " + node.getId() + ": " + e.getMessage());
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
        System.out.println("✅ Добавлена демо-схема: " + demo.getName());
    }
}
