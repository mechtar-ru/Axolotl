package com.agent.orchestrator.service;

import com.agent.orchestrator.model.*;
import com.agent.orchestrator.repository.SchemaRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class SchemaService {

    private final SchemaRepository schemaRepository;
    private final OpenClawClient openClawClient;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final Map<String, CompletableFuture<?>> runningExecutions = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    // Единый конструктор для Spring
    public SchemaService(SchemaRepository schemaRepository,
            OpenClawClient openClawClient,
            ExecutionWebSocketHandler webSocketHandler) {
        this.schemaRepository = schemaRepository;
        this.openClawClient = openClawClient;
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

    private void executeWorkflow(WorkflowSchema schema, AtomicBoolean cancelFlag) {
        System.out.println("▶️ Выполнение схемы: " + schema.getName());
        long startTime = System.currentTimeMillis();

        // Начало выполнения схемы
        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schema.getId(), "system", "STARTED", 0, "Выполнение начано");
            webSocketHandler.sendLog(schema.getId(), "info", "Выполнение схемы начато: " + schema.getName(), null);
        }

        // Получить упорядоченный список узлов (топологическая сортировка)
        List<Node> sortedNodes = getExecutionOrder(schema);

        // Выполнение узлов в правильном порядке
        if (sortedNodes != null && !sortedNodes.isEmpty()) {
            int completedCount = 0;
            for (Node node : sortedNodes) {
                if (cancelFlag.get()) {
                    break;
                }
                long nodeStartTime = System.currentTimeMillis();
                System.out.println("⏸ Начало выполнения узла: " + node.getId() + " (" + node.getName() + ")");

                // Отправка лога о начале выполнения узла
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schema.getId(), "info", "Начало выполнения узла: " + node.getName(), node.getId());
                }

                executeNode(node, schema.getId(), cancelFlag);

                long nodeTime = System.currentTimeMillis() - nodeStartTime;
                completedCount++;

                // Отправка метрик после каждого узла
                long currentTime = System.currentTimeMillis() - startTime;
                double nodesPerSecond = currentTime > 0 ? (double) completedCount / (currentTime / 1000.0) : 0;
                if (webSocketHandler != null) {
                    webSocketHandler.sendMetrics(schema.getId(), sortedNodes.size(), completedCount, currentTime, nodesPerSecond);
                    webSocketHandler.sendLog(schema.getId(), "success", "Узел завершен: " + node.getName() + " (" + nodeTime + "мс)", node.getId());
                }

                System.out.println("✅ Узел завершен: " + node.getId() + " (" + node.getName() + ") - " + nodeTime + "мс");

                // Гарантированное ожидание завершения узла перед следующим
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // Подсчет завершенных узлов
        int nodesCompleted = sortedNodes != null ? sortedNodes.size() : 0;
        if (cancelFlag.get()) {
            // Если выполнение было отменено, считаем только завершенные узлы
            nodesCompleted = (int) sortedNodes.stream()
                    .filter(n -> n.getStatus() == Node.NodeStatus.COMPLETED)
                    .count();
        }

        long totalTime = System.currentTimeMillis() - startTime;

        if (cancelFlag.get()) {
            if (webSocketHandler != null) {
                webSocketHandler.sendError(schema.getId(), "system", "Выполнение остановлено");
                webSocketHandler.sendLog(schema.getId(), "warning", "Выполнение схемы остановлено пользователем", null);
            }
            System.out.println("⚠️ Выполнение схемы отменено: " + schema.getName());
        } else {
            if (webSocketHandler != null) {
                webSocketHandler.sendComplete(schema.getId(), totalTime, nodesCompleted);
                double finalNodesPerSecond = totalTime > 0 ? (double) nodesCompleted / (totalTime / 1000.0) : 0;
                webSocketHandler.sendMetrics(schema.getId(), nodesCompleted, nodesCompleted, totalTime, finalNodesPerSecond);
                webSocketHandler.sendLog(schema.getId(), "success", "Выполнение схемы завершено: " + totalTime + "мс, узлов: " + nodesCompleted, null);
            }
            System.out.println("✅ Выполнение схемы завершено: " + schema.getName() +
                    " (" + totalTime + "мс, " + nodesCompleted + "/" +
                    (sortedNodes != null ? sortedNodes.size() : 0) + " узлов)");
        }
    }

    private List<Node> getExecutionOrder(WorkflowSchema schema) {
        if (schema.getNodes() == null || schema.getNodes().isEmpty()) {
            return new ArrayList<>();
        }

        List<Node> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Map<String, Node> nodeMap = new HashMap<>();
        Map<String, Set<String>> incomingEdges = new HashMap<>();

        // Инициализация
        for (Node node : schema.getNodes()) {
            nodeMap.put(node.getId(), node);
            incomingEdges.put(node.getId(), new HashSet<>());
        }

        // Построение графа входящих зависимостей
        if (schema.getEdges() != null) {
            for (Edge edge : schema.getEdges()) {
                if (incomingEdges.containsKey(edge.getTarget())) {
                    incomingEdges.get(edge.getTarget()).add(edge.getSource());
                }
            }
        }

        // Топологическая сортировка (Kahn's algorithm)
        Queue<String> queue = new LinkedList<>();

        // Найти все узлы ohne входящих зависимостей
        for (Node node : schema.getNodes()) {
            if (incomingEdges.get(node.getId()).isEmpty()) {
                queue.add(node.getId());
                System.out.println("📍 Узел без входящих зависимостей (можно начать): " + node.getId());
            }
        }

        while (!queue.isEmpty()) {
            String nodeId = queue.poll();
            Node node = nodeMap.get(nodeId);
            if (node != null) {
                result.add(node);
                visited.add(nodeId);

                // Найти все зависимые узлы
                for (String depNodeId : nodeMap.keySet()) {
                    Set<String> deps = incomingEdges.get(depNodeId);
                    if (deps != null && deps.contains(nodeId)) {
                        deps.remove(nodeId);
                        if (deps.isEmpty()) {
                            queue.add(depNodeId);
                            System.out.println("📍 Зависимости выполнены для: " + depNodeId);
                        }
                    }
                }
            }
        }

        // Если есть узлы, которые не были добавлены (циклическая зависимость)
        if (result.size() < schema.getNodes().size()) {
            System.out.println("⚠️ ВНИМАНИЕ: Обнаружены циклические зависимости или несвязанные узлы!");
            for (Node node : schema.getNodes()) {
                if (!visited.contains(node.getId())) {
                    result.add(node);
                    System.out.println("⚠️ Добавлен узел с циклической зависимостью: " + node.getId());
                }
            }
        }

        System.out.println("📊 Порядок выполнения узлов:");
        for (int i = 0; i < result.size(); i++) {
            System.out.println("  " + (i + 1) + ". " + result.get(i).getId() + " (" + result.get(i).getName() + ")");
        }

        return result;
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
                // 50%: Отправка запроса
                if (webSocketHandler != null) {
                    webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "Отправка запроса");
                    webSocketHandler.sendLog(schemaId, "info", "Отправка запроса к AI", node.getId());
                }
                if (!sleepWithCancel(500, cancelFlag))
                    return;

                // Имитация вызова AI
                String prompt = node.getData() != null && node.getData().getUserPrompt() != null
                        ? node.getData().getUserPrompt()
                        : "Анализируй данные";
                result = "AI анализ: " + prompt.substring(0, Math.min(50, prompt.length())) + "...";

                // 70%: Обработка ответа
                if (webSocketHandler != null) {
                    webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 70, "Обработка ответа");
                    webSocketHandler.sendLog(schemaId, "info", "Обработка ответа от AI", node.getId());
                }
                if (!sleepWithCancel(400, cancelFlag))
                    return;

            } else if ("source".equals(node.getType())) {
                result = "Данные из источника: " + node.getName();
                if (!sleepWithCancel(300, cancelFlag))
                    return;

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
