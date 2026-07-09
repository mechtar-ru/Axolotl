package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.NodeExecution;
import com.agent.orchestrator.model.WorkflowSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class OutputNodeStrategy implements NodeExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(OutputNodeStrategy.class);
    private final OutputReportingService outputReportingService;

    public OutputNodeStrategy(OutputReportingService outputReportingService) {
        this.outputReportingService = outputReportingService;
    }

    @Override
    public String supportedNodeType() { return "output"; }

    @Override
    public Map<String, Object> executeNode(Node node, NodeExecution nodeExec, WorkflowSchema schema,
            List<Node> allNodes, List<Edge> edges,
            Map<String, Object> executionContext, String schemaId) {
        String modeStr = executionContext != null ? (String) executionContext.getOrDefault("mode", "EXECUTE") : "EXECUTE";
        com.agent.orchestrator.model.ExecutionMode mode;
        try {
            mode = com.agent.orchestrator.model.ExecutionMode.valueOf(modeStr);
        } catch (Exception e) {
            mode = com.agent.orchestrator.model.ExecutionMode.EXECUTE;
        }
        String result = outputReportingService.executeOutputNode(node, schemaId, mode);
        return Map.of("result", result);
    }
}
