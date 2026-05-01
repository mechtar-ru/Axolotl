package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.Edge;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;



public class WorkflowDocumenter {
    public String documentWorkflow(WorkflowSchema schema) {
        if (schema == null) return "No workflow to document";

        StringBuilder doc = new StringBuilder();
        String name = schema.getName() != null ? schema.getName() : "Untitled";
        doc.append("Workflow: ").append(name).append("\n");

        List<Node> nodes = schema.getNodes();
        List<Edge> edges = schema.getEdges();
        int nodeCount = nodes != null ? nodes.size() : 0;
        int edgeCount = edges != null ? edges.size() : 0;
        doc.append("Nodes: ").append(nodeCount).append(", Edges: ").append(edgeCount).append("\n\n");

        if (nodes != null) {
            doc.append("Nodes:\n");
            for (Node n : nodes) {
                doc.append("  - ").append(describeNode(n)).append("\n");
            }
        }

        if (edges != null && !edges.isEmpty()) {
            doc.append("\nData Flow:\n");
            Map<String, String> nodeIdMap = nodes != null ? 
                nodes.stream().collect(Collectors.toMap(Node::getId, Node::getType)) : Map.of();
            for (Edge e : edges) {
                String srcType = nodeIdMap.getOrDefault(e.getSource(), "unknown");
                String tgtType = nodeIdMap.getOrDefault(e.getTarget(), "unknown");
                doc.append("  - ").append(srcType).append(" (").append(e.getSource())
                   .append(") → ").append(tgtType).append(" (").append(e.getTarget()).append(")\n");
            }
        }
        return doc.toString();
    }

    private String describeNode(Node node) {
        if (node == null) return "Unknown node";
        String type = node.getType() != null ? node.getType() : "unknown";
        return switch (type) {
            case "source" -> "Source node (id: " + node.getId() + ") provides input data";
            case "agent" -> "Agent node (id: " + node.getId() + ") processes data using AI";
            case "output" -> "Output node (id: " + node.getId() + ") delivers final results";
            default -> type + " node (id: " + node.getId() + ")";
        };
    }
}
