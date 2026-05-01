package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.Edge;

import java.util.ArrayList;
import java.util.List;



public class WorkflowModifier {
    public List<WorkflowModification> generateModifications(WorkflowSelfAnalyzer.WorkflowAnalysisReport report, WorkflowSchema schema) {
        List<WorkflowModification> mods = new ArrayList<>();
        if (report == null || schema == null) return mods;

        for (String nodeId : report.getIsolatedNodes()) {
            WorkflowModification mod = new WorkflowModification();
            mod.setType(WorkflowModification.ModificationType.REMOVE_ISOLATED_NODE);
            mod.setTargetNodeId(nodeId);
            mod.setDetails("Remove isolated node " + nodeId);
            mods.add(mod);
        }

        if (report.hasCycles()) {
            String edgeId = findCycleEdge(schema);
            if (edgeId != null) {
                WorkflowModification mod = new WorkflowModification();
                mod.setType(WorkflowModification.ModificationType.BREAK_CYCLE);
                mod.setEdgeId(edgeId);
                mod.setDetails("Break cycle by removing edge " + edgeId);
                mods.add(mod);
            }
        }

        for (String nodeId : report.getBottlenecks()) {
            WorkflowModification mod = new WorkflowModification();
            mod.setType(WorkflowModification.ModificationType.SPLIT_BOTTLENECK);
            mod.setTargetNodeId(nodeId);
            mod.setDetails("Split bottleneck node " + nodeId);
            mods.add(mod);
        }
        return mods;
    }

    public boolean isModificationSafe(WorkflowModification mod, WorkflowSchema schema) {
        if (mod == null || schema == null) return false;
        return switch (mod.getType()) {
            case REMOVE_ISOLATED_NODE -> findNode(schema, mod.getTargetNodeId()) != null && isIsolated(mod.getTargetNodeId(), schema);
            case BREAK_CYCLE -> findEdge(schema, mod.getEdgeId()) != null;
            case SPLIT_BOTTLENECK -> findNode(schema, mod.getTargetNodeId()) != null;
        };
    }

    public WorkflowSchema applyModification(WorkflowSchema schema, WorkflowModification mod) {
        if (!isModificationSafe(mod, schema)) return schema;
        WorkflowSchema modified = copySchema(schema);
        switch (mod.getType()) {
            case REMOVE_ISOLATED_NODE -> modified.getNodes().removeIf(n -> n.getId().equals(mod.getTargetNodeId()));
            case BREAK_CYCLE -> modified.getEdges().removeIf(e -> e.getId().equals(mod.getEdgeId()));
            case SPLIT_BOTTLENECK -> {
                Node orig = findNode(modified, mod.getTargetNodeId());
                if (orig != null) {
                    Node split = new Node();
                    split.setId(orig.getId() + "_split");
                    split.setType(orig.getType());
                    modified.getNodes().add(split);
                }
            }
        }
        return modified;
    }

    private String findCycleEdge(WorkflowSchema schema) {
        if (schema.getEdges() == null || schema.getEdges().isEmpty()) return null;
        return schema.getEdges().get(0).getId(); // Simplified for demo
    }

    private Node findNode(WorkflowSchema schema, String nodeId) {
        if (schema.getNodes() == null) return null;
        return schema.getNodes().stream().filter(n -> n.getId().equals(nodeId)).findFirst().orElse(null);
    }

    private Edge findEdge(WorkflowSchema schema, String edgeId) {
        if (schema.getEdges() == null) return null;
        return schema.getEdges().stream().filter(e -> e.getId().equals(edgeId)).findFirst().orElse(null);
    }

    private boolean isIsolated(String nodeId, WorkflowSchema schema) {
        if (schema.getEdges() == null) return true;
        return schema.getEdges().stream().noneMatch(e -> e.getSource().equals(nodeId) || e.getTarget().equals(nodeId));
    }

    private WorkflowSchema copySchema(WorkflowSchema schema) {
        WorkflowSchema copy = new WorkflowSchema();
        copy.setId(schema.getId());
        copy.setName(schema.getName());
        if (schema.getNodes() != null) {
            List<Node> nodes = new ArrayList<>();
            for (Node n : schema.getNodes()) {
                Node newNode = new Node(); newNode.setId(n.getId()); newNode.setType(n.getType());
                nodes.add(newNode);
            }
            copy.setNodes(nodes);
        }
        if (schema.getEdges() != null) {
            List<Edge> edges = new ArrayList<>();
            for (Edge e : schema.getEdges()) {
                Edge newEdge = new Edge(); newEdge.setId(e.getId()); newEdge.setSource(e.getSource()); newEdge.setTarget(e.getTarget());
                edges.add(newEdge);
            }
            copy.setEdges(edges);
        }
        return copy;
    }

    public static class WorkflowModification {
        public enum ModificationType { REMOVE_ISOLATED_NODE, BREAK_CYCLE, SPLIT_BOTTLENECK }
        private ModificationType type;
        private String targetNodeId;
        private String edgeId;
        private String details;

        public ModificationType getType() { return type; }
        public void setType(ModificationType type) { this.type = type; }
        public String getTargetNodeId() { return targetNodeId; }
        public void setTargetNodeId(String id) { this.targetNodeId = id; }
        public String getEdgeId() { return edgeId; }
        public void setEdgeId(String id) { this.edgeId = id; }
        public String getDetails() { return details; }
        public void setDetails(String d) { this.details = d; }
    }
}
