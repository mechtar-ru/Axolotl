package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.NodeExecution;
import com.agent.orchestrator.model.WorkflowSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class SourceNodeStrategy implements NodeExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(SourceNodeStrategy.class);
    private final ExecutionUtilityService utilityService;

    public SourceNodeStrategy(ExecutionUtilityService utilityService) {
        this.utilityService = utilityService;
    }

    @Override
    public String supportedNodeType() { return "source"; }

    @Override
    public Map<String, Object> executeNode(Node node, NodeExecution nodeExec, WorkflowSchema schema,
            java.util.List<Node> allNodes, java.util.List<Edge> edges,
            Map<String, Object> executionContext, String schemaId) {
        String result = utilityService.handleSourceNode(node, schemaId);
        return Map.of("result", result);
    }
}
