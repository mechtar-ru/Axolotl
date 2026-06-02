package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.Stage;
import com.agent.orchestrator.model.WorkflowSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class PipelineBuilder {

    private static final Logger log = LoggerFactory.getLogger(PipelineBuilder.class);

    private final Neo4jSchemaRepository schemaRepository;

    public PipelineBuilder(Neo4jSchemaRepository schemaRepository) {
        this.schemaRepository = schemaRepository;
    }

    public WorkflowSchema buildNodes(String schemaId) {
        WorkflowSchema schema = schemaRepository.findById(schemaId);
        if (schema == null) {
            throw new RuntimeException("Schema not found: " + schemaId);
        }
        if (schema.getPipeline() == null || schema.getPipeline().getStages() == null) {
            throw new RuntimeException("Schema has no pipeline definition");
        }

        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        Map<String, String> stageToNode = new HashMap<>();

        int i = 0;
        for (Stage stage : schema.getPipeline().getStages()) {
            String nodeId = stage.getId() != null ? stage.getId() : "stage-" + UUID.randomUUID().toString().substring(0, 8);

            Node node = new Node();
            node.setId(nodeId);
            node.setType(stage.getNodeType() != null ? stage.getNodeType() : "agent");
            node.setName(stage.getName() != null ? stage.getName() : "Stage " + (i + 1));
            node.setStatus(Node.NodeStatus.IDLE);

            Node.NodeData data = new Node.NodeData();
            data.setModel(stage.getModel());
            data.setSystemPrompt(stage.getSystemPrompt());
            data.setUserPrompt(stage.getUserPrompt());
            data.setConfig(stage.getConfig() != null ? new HashMap<>(stage.getConfig()) : null);
            if (stage.getFallbackModels() != null && !stage.getFallbackModels().isEmpty()) {
                if (data.getConfig() == null) data.setConfig(new HashMap<>());
                data.getConfig().put("fallbackModels", new ArrayList<>(stage.getFallbackModels()));
            }
            data.setEnabledTools(stage.getEnabledTools());
            if (stage.getAgentType() != null) data.setAgentType(stage.getAgentType());

            if (stage.getSubagentSchemaId() != null) {
                if (data.getConfig() == null) data.setConfig(new HashMap<>());
                data.getConfig().put("subagentSchemaId", stage.getSubagentSchemaId());
            }
            if (stage.getLoopCondition() != null) {
                data.setLoopCondition(stage.getLoopCondition());
                data.setMaxIterations(stage.getMaxIterations());
            }

            node.setData(data);

            Node.Position pos = new Node.Position();
            pos.setX(stage.getPositionX() != 0 ? stage.getPositionX() : 100 + (i * 300));
            pos.setY(stage.getPositionY() != 0 ? stage.getPositionY() : 200);
            node.setPosition(pos);

            node.setInputPorts(List.of("in"));
            node.setOutputPorts(List.of("out"));

            nodes.add(node);
            stageToNode.put(stage.getId(), nodeId);
            i++;
        }

        for (Stage stage : schema.getPipeline().getStages()) {
            if (stage.getDependencies() != null) {
                String targetId = stageToNode.get(stage.getId());
                for (String depId : stage.getDependencies()) {
                    String sourceId = stageToNode.get(depId);
                    if (sourceId != null && targetId != null) {
                        Edge edge = new Edge();
                        edge.setId("e-" + sourceId + "-" + targetId);
                        edge.setSource(sourceId);
                        edge.setTarget(targetId);
                        edge.setSourcePort("out");
                        edge.setTargetPort("in");
                        edges.add(edge);
                    }
                }
            }
        }

        schema.setNodes(nodes);
        schema.setEdges(edges);
        schemaRepository.save(schema);
        log.info("Built {} nodes and {} edges from pipeline for schema {}", nodes.size(), edges.size(), schemaId);
        return schema;
    }
}
