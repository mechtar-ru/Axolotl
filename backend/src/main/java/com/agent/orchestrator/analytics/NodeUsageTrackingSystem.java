package com.agent.orchestrator.analytics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks node usage during workflow execution.
 * 
 * Integration: Hook into workflow execution engine to record:
 * - onNodeStart(nodeId): call recordNodeStart
 * - onNodeSuccess(nodeId, executionTime): call recordNodeSuccess
 * - onNodeFailure(nodeId): call recordNodeFailure
 */


public class NodeUsageTrackingSystem {
    private final Map<String, NodePerformanceMetrics> metricsMap = new ConcurrentHashMap<>();

    /**
     * Records that a node has started execution.
     */
    public void recordNodeStart(String nodeId) {
        NodePerformanceMetrics metrics = metricsMap.computeIfAbsent(nodeId, 
            k -> new NodePerformanceMetrics(nodeId));
        metrics.incrementUtilization();
    }

    /**
     * Records successful node execution.
     */
    public void recordNodeSuccess(String nodeId, long executionTimeMs) {
        NodePerformanceMetrics metrics = metricsMap.computeIfAbsent(nodeId, 
            k -> new NodePerformanceMetrics(nodeId));
        metrics.recordSuccess(executionTimeMs);
    }

    /**
     * Records failed node execution.
     */
    public void recordNodeFailure(String nodeId) {
        NodePerformanceMetrics metrics = metricsMap.computeIfAbsent(nodeId, 
            k -> new NodePerformanceMetrics(nodeId));
        metrics.recordFailure();
    }

    /**
     * Records execution time for a node (if not already recorded by success/failure).
     */
    public void recordExecutionTime(String nodeId, long executionTimeMs) {
        NodePerformanceMetrics metrics = metricsMap.computeIfAbsent(nodeId, 
            k -> new NodePerformanceMetrics(nodeId));
        metrics.recordExecutionTime(executionTimeMs);
    }

    /**
     * Gets metrics for a specific node.
     */
    public NodePerformanceMetrics getMetrics(String nodeId) {
        return metricsMap.get(nodeId);
    }

    /**
     * Gets all tracked metrics.
     */
    public Map<String, NodePerformanceMetrics> getAllMetrics() {
        return new HashMap<>(metricsMap);
    }

    /**
     * Clears all tracked metrics.
     */
    public void clear() {
        metricsMap.clear();
    }
}
