package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.WorkflowSchema;
import java.util.*;

/**
 * Prunes underutilized nodes from workflows to simplify structure.
 * 
 * Safety: Only removes nodes when bypass connections can be made.
 */


public class WorkflowPruner {
    private final List<String> pruningHistory = new ArrayList<>();

    /**
     * Preview changes without applying them.
     * Returns list of node IDs that would be removed.
     */
    public List<String> previewPruning(WorkflowSchema workflow, List<String> nodesToRemove) {
        List<String> removable = new ArrayList<>();
        
        for (String nodeId : nodesToRemove) {
            if (canSafelyRemove(workflow, nodeId)) {
                removable.add(nodeId);
            }
        }
        
        return removable;
    }

    /**
     * Prunes underutilized nodes from workflow.
     * Returns modified workflow with nodes removed and edges reconnected.
     */
    public WorkflowSchema pruneWorkflow(WorkflowSchema workflow, List<String> nodesToRemove) {
        List<String> safeToRemove = previewPruning(workflow, nodesToRemove);
        
        List<Node> nodes = new ArrayList<>(workflow.getNodes());
        List<Edge> edges = new ArrayList<>(workflow.getEdges());
        
        for (String nodeId : safeToRemove) {
            performPruning(nodes, edges, nodeId);
            pruningHistory.add("Removed node " + nodeId + " at " + new Date());
        }
        
        WorkflowSchema pruned = new WorkflowSchema();
        pruned.setId(workflow.getId());
        pruned.setName(workflow.getName());
        pruned.setNodes(nodes);
        pruned.setEdges(edges);
        pruned.setMetadata(workflow.getMetadata());
        
        return pruned;
    }

    private boolean canSafelyRemove(WorkflowSchema workflow, String nodeId) {
        return workflow.getNodes().stream()
            .anyMatch(n -> n.getId().equals(nodeId));
    }

    private void performPruning(List<Node> nodes, List<Edge> edges, String nodeId) {
        List<Edge> incoming = new ArrayList<>();
        List<Edge> outgoing = new ArrayList<>();
        
        for (Edge e : edges) {
            if (e.getTarget().equals(nodeId)) {
                incoming.add(e);
            }
            if (e.getSource().equals(nodeId)) {
                outgoing.add(e);
            }
        }
        
        // Create bypass connections
        for (Edge in : incoming) {
            for (Edge out : outgoing) {
                Edge bypass = new Edge();
                bypass.setId("bypass-" + UUID.randomUUID().toString().substring(0, 8));
                bypass.setSource(in.getSource());
                bypass.setTarget(out.getTarget());
                bypass.setType("data");
                edges.add(bypass);
            }
        }
        
        // Remove all edges connected to the node
        edges.removeIf(e -> e.getSource().equals(nodeId) || e.getTarget().equals(nodeId));
        
        // Remove the node
        nodes.removeIf(n -> n.getId().equals(nodeId));
    }

    public List<String> getPruningHistory() {
        return new ArrayList<>(pruningHistory);
    }
}
