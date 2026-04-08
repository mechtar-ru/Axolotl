package com.agent.orchestrator.service;

import com.agent.orchestrator.model.*;
import com.agent.orchestrator.repository.SchemaRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
public class SchemaService {
    
    private final SchemaRepository schemaRepository;
    private final OpenClawClient openClawClient;
    private final ExecutionWebSocketHandler webSocketHandler;
    
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
        if (schema == null) return "";
        
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
        if (schema == null) return;
        
        if (schema.getNodes() != null) {
            for (Node node : schema.getNodes()) {
                node.setStatus(Node.NodeStatus.IDLE);
            }
        }
        
        CompletableFuture.runAsync(() -> executeWorkflow(schema));
    }
    
    private void executeWorkflow(WorkflowSchema schema) {
        System.out.println("▶️ Выполнение схемы: " + schema.getName());
        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schema.getId(), "system", "RUNNING", 50, "Выполнение начато");
        }
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
        data.setUserPrompt("Ты опытный аналитик. Проанализируй предоставленные данные и сделай выводы. Ответ должен быть на русском языке.");
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
