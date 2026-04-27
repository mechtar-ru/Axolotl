package com.agent.orchestrator.analytics;

import java.time.Instant;

/**
 * Tracks performance metrics for workflow nodes.
 * 
 * Integration point: WorkflowAnalyzer will use this class to:
 * - Get utilizationCount to identify underutilized nodes (<5% threshold)
 * - Get successRate to identify problematic nodes
 * - Get averageExecutionTimeMs to find performance bottlenecks
 * - Get lastExecutionTimestamp to identify stale nodes
 * 
 * Used by WorkflowAnalyzer and adaptive workflow engine for optimization.
 */


public class NodePerformanceMetrics {
    private final String nodeId;
    private int utilizationCount;
    private int successCount;
    private int failureCount;
    private long totalExecutionTimeMs;
    private int executionCount;
    private Instant lastExecutionTimestamp;

    public NodePerformanceMetrics(String nodeId) {
        this.nodeId = nodeId;
        this.utilizationCount = 0;
        this.successCount = 0;
        this.failureCount = 0;
        this.totalExecutionTimeMs = 0;
        this.executionCount = 0;
        this.lastExecutionTimestamp = null;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void incrementUtilization() {
        utilizationCount++;
    }

    public void recordSuccess(long executionTimeMs) {
        successCount++;
        executionCount++;
        totalExecutionTimeMs += executionTimeMs;
        lastExecutionTimestamp = Instant.now();
    }

    public void recordFailure() {
        failureCount++;
        executionCount++;
        lastExecutionTimestamp = Instant.now();
    }

    public void recordExecutionTime(long executionTimeMs) {
        executionCount++;
        totalExecutionTimeMs += executionTimeMs;
    }

    public double getSuccessRate() {
        if (executionCount == 0) {
            return 0.0;
        }
        return (double) successCount / executionCount;
    }

    public double getAverageExecutionTimeMs() {
        if (executionCount == 0) {
            return 0.0;
        }
        return (double) totalExecutionTimeMs / executionCount;
    }

    public int getUtilizationCount() {
        return utilizationCount;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public int getExecutionCount() {
        return executionCount;
    }

    public Instant getLastExecutionTimestamp() {
        return lastExecutionTimestamp;
    }

    public double getUtilizationRate() {
        if (utilizationCount == 0) {
            return 0.0;
        }
        return (double) executionCount / utilizationCount;
    }
}
