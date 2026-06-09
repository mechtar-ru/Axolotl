package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.NodeExecution;
import com.agent.orchestrator.model.WorkflowSchema;
import java.util.List;
import java.util.Map;

/**
 * Strategy interface for executing different node types in a workflow.
 * Each concrete strategy handles one specific node type (e.g., "agent", "verifier").
 * Strategies are automatically collected by Spring and registered in NodeRouter.
 */
public interface NodeExecutionStrategy {
    /**
     * @return the node type this strategy handles (e.g., "agent", "verifier", "review")
     */
    String supportedNodeType();

    /**
     * Execute a node and return the result as a map.
     * The map must contain at least a "result" key with the string result.
     *
     * @param node the node to execute
     * @param nodeExec the persisted node execution record (may be null)
     * @param schema the full workflow schema
     * @param allNodes all nodes in the schema
     * @param edges all edges in the schema
     * @param executionContext shared execution context
     * @param schemaId the schema identifier
     * @return map containing execution result (key "result") and optional metadata
     */
    Map<String, Object> executeNode(Node node, NodeExecution nodeExec, WorkflowSchema schema,
                                     List<Node> allNodes, List<Edge> edges,
                                     Map<String, Object> executionContext, String schemaId);

    /**
     * Whether this strategy supports streaming (WebSocket token events).
     */
    default boolean supportsStreaming() { return false; }
}
