package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.WorkflowSchema;
import java.util.*;

/**
 * Computes complexity score for workflows based on structural properties.
 * 
 * Integration points:
 * - computeComplexity(workflow) - computes raw complexity score
 * - normalizeScore(rawScore) - normalizes score to 0-1 range
 * - getComplexityFactors(workflow) - returns individual factor scores
 * 
 * Usage: Integrate with CognitiveLoadEstimator to contribute
 * to overall cognitive load estimation.
 * Formula: complexity = w1 * nodeCount + w2 * depth + w3 * edgeCount
 */


public class WorkloadComplexityScorer {
    
    private static final double DEFAULT_NODE_WEIGHT = 0.4;
    private static final double DEFAULT_DEPTH_WEIGHT = 0.3;
    private static final double DEFAULT_EDGE_WEIGHT = 0.3;
    
    private static final double DEFAULT_MAX_COMPLEXITY = 100.0;
    
    private double nodeWeight;
    private double depthWeight;
    private double edgeWeight;
    
    public WorkloadComplexityScorer() {
        this.nodeWeight = DEFAULT_NODE_WEIGHT;
        this.depthWeight = DEFAULT_DEPTH_WEIGHT;
        this.edgeWeight = DEFAULT_EDGE_WEIGHT;
    }
    
    public WorkloadComplexityScorer(double nodeWeight, double depthWeight, double edgeWeight) {
        this.nodeWeight = nodeWeight;
        this.depthWeight = depthWeight;
        this.edgeWeight = edgeWeight;
    }
    
    /**
     * Computes raw complexity score for a workflow.
     */
    public double computeComplexity(WorkflowSchema workflow) {
        if (workflow == null || workflow.getNodes() == null) {
            return 0.0;
        }
        
        int nodeCount = workflow.getNodes().size();
        int depth = calculateDepth(workflow);
        int edgeCount = workflow.getEdges() != null ? workflow.getEdges().size() : 0;
        
        // Simple weighted formula
        double score = (nodeWeight * nodeCount) + 
                       (depthWeight * depth) + 
                       (edgeWeight * edgeCount);
        
        return score;
    }
    
    /**
     * Normalizes score to 0-1 range.
     */
    public double normalizeScore(double rawScore) {
        if (rawScore <= 0) {
            return 0.0;
        }
        
        double normalized = rawScore / DEFAULT_MAX_COMPLEXITY;
        return Math.min(1.0, normalized);
    }
    
    /**
     * Returns normalized complexity score for workflow.
     */
    public double getNormalizedComplexity(WorkflowSchema workflow) {
        double rawScore = computeComplexity(workflow);
        return normalizeScore(rawScore);
    }
    
    /**
     * Returns individual complexity factors.
     */
    public Map<String, Double> getComplexityFactors(WorkflowSchema workflow) {
        Map<String, Double> factors = new HashMap<>();
        
        if (workflow == null) {
            factors.put("nodeCount", 0.0);
            factors.put("depth", 0.0);
            factors.put("edgeCount", 0.0);
            factors.put("branchingFactor", 0.0);
            return factors;
        }
        
        int nodeCount = workflow.getNodes() != null ? workflow.getNodes().size() : 0;
        int depth = calculateDepth(workflow);
        int edgeCount = workflow.getEdges() != null ? workflow.getEdges().size() : 0;
        double branchingFactor = calculateBranchingFactor(workflow);
        
        factors.put("nodeCount", (double) nodeCount);
        factors.put("depth", (double) depth);
        factors.put("edgeCount", (double) edgeCount);
        factors.put("branchingFactor", branchingFactor);
        
        return factors;
    }
    
    /**
     * Calculates workflow depth (longest path from source to sink).
     */
    public int calculateDepth(WorkflowSchema workflow) {
        if (workflow == null || workflow.getNodes() == null || workflow.getNodes().isEmpty()) {
            return 0;
        }
        
        List<Node> nodes = workflow.getNodes();
        List<Edge> edges = workflow.getEdges();
        
        if (edges == null || edges.isEmpty()) {
            return 1;
        }
        
        // Build adjacency map
        Map<String, List<String>> adjacency = new HashMap<>();
        for (Node node : nodes) {
            adjacency.put(node.getId(), new ArrayList<>());
        }
        
        for (Edge edge : edges) {
            List<String> targets = adjacency.get(edge.getSource());
            if (targets != null) {
                targets.add(edge.getTarget());
            }
        }
        
        // Find source nodes (no incoming edges)
        Map<String, Boolean> hasIncoming = new HashMap<>();
        for (Edge edge : edges) {
            hasIncoming.put(edge.getTarget(), true);
        }
        
        List<String> sources = new ArrayList<>();
        for (Node node : nodes) {
            if (!hasIncoming.containsKey(node.getId())) {
                sources.add(node.getId());
            }
        }
        
        if (sources.isEmpty()) {
            sources.add(nodes.get(0).getId());
        }
        
        // DFS to find max depth
        Map<String, Integer> memo = new HashMap<>();
        int maxDepth = 0;
        
        for (String source : sources) {
            maxDepth = Math.max(maxDepth, dfsDepth(source, adjacency, memo));
        }
        
        return maxDepth;
    }
    
    private int dfsDepth(String nodeId, Map<String, List<String>> adjacency, Map<String, Integer> memo) {
        if (memo.containsKey(nodeId)) {
            return memo.get(nodeId);
        }
        
        List<String> children = adjacency.get(nodeId);
        if (children == null || children.isEmpty()) {
            return 1;
        }
        
        int maxChildDepth = 0;
        for (String child : children) {
            maxChildDepth = Math.max(maxChildDepth, dfsDepth(child, adjacency, memo));
        }
        
        int depth = 1 + maxChildDepth;
        memo.put(nodeId, depth);
        return depth;
    }
    
    /**
     * Calculates average branching factor.
     */
    public double calculateBranchingFactor(WorkflowSchema workflow) {
        if (workflow == null || workflow.getEdges() == null || workflow.getNodes() == null) {
            return 0.0;
        }
        
        int nodeCount = workflow.getNodes().size();
        int edgeCount = workflow.getEdges().size();
        
        if (nodeCount == 0) {
            return 0.0;
        }
        
        return (double) edgeCount / nodeCount;
    }
    
    public double getNodeWeight() {
        return nodeWeight;
    }
    
    public void setNodeWeight(double nodeWeight) {
        this.nodeWeight = nodeWeight;
    }
    
    public double getDepthWeight() {
        return depthWeight;
    }
    
    public void setDepthWeight(double depthWeight) {
        this.depthWeight = depthWeight;
    }
    
    public double getEdgeWeight() {
        return edgeWeight;
    }
    
    public void setEdgeWeight(double edgeWeight) {
        this.edgeWeight = edgeWeight;
    }
}