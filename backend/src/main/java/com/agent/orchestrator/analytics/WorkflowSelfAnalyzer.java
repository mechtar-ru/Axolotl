package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.Edge;

import java.util.*;



public class WorkflowSelfAnalyzer {
    public WorkflowAnalysisReport analyze(WorkflowSchema schema) {
        WorkflowAnalysisReport report = new WorkflowAnalysisReport();
        if (schema == null || schema.getNodes() == null || schema.getEdges() == null) {
            return report;
        }

        List<Node> nodes = schema.getNodes();
        List<Edge> edges = schema.getEdges();

        report.setNodeCount(nodes.size());
        report.setEdgeCount(edges.size());
        report.setDensity(calculateDensity(nodes.size(), edges.size()));
        report.setDepth(calculateLongestPath(nodes, edges));
        report.setHasCycles(hasCycles(nodes, edges));
        report.setBottlenecks(findBottlenecks(nodes, edges));
        report.setIsolatedNodes(findIsolatedNodes(nodes, edges));

        List<String> issues = new ArrayList<>();
        if (report.hasCycles()) issues.add("Workflow contains cycles");
        if (!report.getBottlenecks().isEmpty()) issues.add("Bottlenecks: " + report.getBottlenecks());
        if (!report.getIsolatedNodes().isEmpty()) issues.add("Isolated nodes: " + report.getIsolatedNodes());
        report.setIssues(issues);

        return report;
    }

    private double calculateDensity(int nodeCount, int edgeCount) {
        if (nodeCount <= 1) return 0.0;
        return (double) edgeCount / (nodeCount * (nodeCount - 1));
    }

    private int calculateLongestPath(List<Node> nodes, List<Edge> edges) {
        Map<String, List<String>> adj = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        Set<String> nodeIds = new HashSet<>();

        for (Node n : nodes) {
            nodeIds.add(n.getId());
            adj.put(n.getId(), new ArrayList<>());
            inDegree.put(n.getId(), 0);
        }

        for (Edge e : edges) {
            if (nodeIds.contains(e.getSource()) && nodeIds.contains(e.getTarget())) {
                adj.get(e.getSource()).add(e.getTarget());
                inDegree.put(e.getTarget(), inDegree.get(e.getTarget()) + 1);
            }
        }

        Queue<String> queue = new LinkedList<>();
        Map<String, Integer> dist = new HashMap<>();
        for (String id : nodeIds) {
            if (inDegree.get(id) == 0) {
                queue.add(id);
                dist.put(id, 1);
            }
        }

        int maxDepth = 0;
        while (!queue.isEmpty()) {
            String u = queue.poll();
            maxDepth = Math.max(maxDepth, dist.get(u));
            for (String v : adj.get(u)) {
                inDegree.put(v, inDegree.get(v) - 1);
                if (inDegree.get(v) == 0) queue.add(v);
                dist.put(v, Math.max(dist.getOrDefault(v, 0), dist.get(u) + 1));
            }
        }
        return maxDepth;
    }

    private boolean hasCycles(List<Node> nodes, List<Edge> edges) {
        Map<String, List<String>> adj = new HashMap<>();
        Set<String> nodeIds = new HashSet<>();
        for (Node n : nodes) {
            nodeIds.add(n.getId());
            adj.put(n.getId(), new ArrayList<>());
        }
        for (Edge e : edges) {
            if (nodeIds.contains(e.getSource()) && nodeIds.contains(e.getTarget())) {
                adj.get(e.getSource()).add(e.getTarget());
            }
        }

        Set<String> visited = new HashSet<>();
        Set<String> recStack = new HashSet<>();
        for (String id : nodeIds) {
            if (hasCyclesDfs(id, adj, visited, recStack)) return true;
        }
        return false;
    }

    private boolean hasCyclesDfs(String node, Map<String, List<String>> adj, Set<String> visited, Set<String> recStack) {
        if (recStack.contains(node)) return true;
        if (visited.contains(node)) return false;
        visited.add(node);
        recStack.add(node);
        for (String neighbor : adj.getOrDefault(node, new ArrayList<>())) {
            if (hasCyclesDfs(neighbor, adj, visited, recStack)) return true;
        }
        recStack.remove(node);
        return false;
    }

    private List<String> findBottlenecks(List<Node> nodes, List<Edge> edges) {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, Integer> outDegree = new HashMap<>();
        Set<String> nodeIds = new HashSet<>();
        for (Node n : nodes) {
            nodeIds.add(n.getId());
            inDegree.put(n.getId(), 0);
            outDegree.put(n.getId(), 0);
        }
        for (Edge e : edges) {
            if (nodeIds.contains(e.getSource()) && nodeIds.contains(e.getTarget())) {
                outDegree.put(e.getSource(), outDegree.get(e.getSource()) + 1);
                inDegree.put(e.getTarget(), inDegree.get(e.getTarget()) + 1);
            }
        }
        List<String> bottlenecks = new ArrayList<>();
        for (String id : nodeIds) {
            if (inDegree.get(id) + outDegree.get(id) >= 3) bottlenecks.add(id);
        }
        return bottlenecks;
    }

    private List<String> findIsolatedNodes(List<Node> nodes, List<Edge> edges) {
        Set<String> nodeIds = new HashSet<>();
        for (Node n : nodes) nodeIds.add(n.getId());
        Set<String> connected = new HashSet<>();
        for (Edge e : edges) {
            connected.add(e.getSource());
            connected.add(e.getTarget());
        }
        List<String> isolated = new ArrayList<>();
        for (String id : nodeIds) {
            if (!connected.contains(id)) isolated.add(id);
        }
        return isolated;
    }

    public static class WorkflowAnalysisReport {
        private int nodeCount;
        private int edgeCount;
        private double density;
        private int depth;
        private boolean hasCycles;
        private List<String> bottlenecks = new ArrayList<>();
        private List<String> isolatedNodes = new ArrayList<>();
        private List<String> issues = new ArrayList<>();

        public int getNodeCount() { return nodeCount; }
        public void setNodeCount(int nodeCount) { this.nodeCount = nodeCount; }
        public int getEdgeCount() { return edgeCount; }
        public void setEdgeCount(int edgeCount) { this.edgeCount = edgeCount; }
        public double getDensity() { return density; }
        public void setDensity(double density) { this.density = density; }
        public int getDepth() { return depth; }
        public void setDepth(int depth) { this.depth = depth; }
        public boolean hasCycles() { return hasCycles; }
        public void setHasCycles(boolean hasCycles) { this.hasCycles = hasCycles; }
        public List<String> getBottlenecks() { return bottlenecks; }
        public void setBottlenecks(List<String> bottlenecks) { this.bottlenecks = bottlenecks; }
        public List<String> getIsolatedNodes() { return isolatedNodes; }
        public void setIsolatedNodes(List<String> isolatedNodes) { this.isolatedNodes = isolatedNodes; }
        public List<String> getIssues() { return issues; }
        public void setIssues(List<String> issues) { this.issues = issues; }
    }
}
