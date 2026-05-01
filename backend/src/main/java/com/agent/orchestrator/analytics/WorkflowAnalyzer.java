package com.agent.orchestrator.analytics;

import java.util.*;


/**
 * Analyzes workflow execution patterns to identify optimization opportunities.
 * 
 * Uses NodePerformanceMetrics to find underutilized nodes and generate recommendations.
 * 
 * Integration point: Adaptive workflow engine will use this class to:
 * - Get underutilized nodes for pruning
 * - Get utilization rates for each node
 * - Receive optimization recommendations
 */

public class WorkflowAnalyzer {
    private double utilizationThreshold;

    public WorkflowAnalyzer() {
        this.utilizationThreshold = 0.05; // 5% default
    }

    public WorkflowAnalyzer(double utilizationThreshold) {
        this.utilizationThreshold = utilizationThreshold;
    }

    /**
     * Analyzes workflow and returns nodes with utilization below threshold.
     */
    public List<String> findUnderutilizedNodes(Map<String, NodePerformanceMetrics> metricsMap) {
        List<String> underutilized = new ArrayList<>();
        
        for (Map.Entry<String, NodePerformanceMetrics> entry : metricsMap.entrySet()) {
            NodePerformanceMetrics metrics = entry.getValue();
            if (metrics.getUtilizationRate() < utilizationThreshold) {
                underutilized.add(entry.getKey());
            }
        }
        
        return underutilized;
    }

    /**
     * Calculates utilization rate for each node.
     */
    public Map<String, Double> calculateUtilizationRates(Map<String, NodePerformanceMetrics> metricsMap) {
        Map<String, Double> rates = new HashMap<>();
        
        for (Map.Entry<String, NodePerformanceMetrics> entry : metricsMap.entrySet()) {
            rates.put(entry.getKey(), entry.getValue().getUtilizationRate());
        }
        
        return rates;
    }

    /**
     * Generates optimization recommendations based on analysis.
     */
    public List<String> generateRecommendations(Map<String, NodePerformanceMetrics> metricsMap) {
        List<String> recommendations = new ArrayList<>();
        
        List<String> underutilized = findUnderutilizedNodes(metricsMap);
        for (String nodeId : underutilized) {
            NodePerformanceMetrics metrics = metricsMap.get(nodeId);
            recommendations.add("Consider removing node " + nodeId + 
                " (utilization rate: " + (metrics.getUtilizationRate() * 100) + "%)");
        }
        
        return recommendations;
    }

    public double getUtilizationThreshold() {
        return utilizationThreshold;
    }

    public void setUtilizationThreshold(double utilizationThreshold) {
        this.utilizationThreshold = utilizationThreshold;
    }
}
