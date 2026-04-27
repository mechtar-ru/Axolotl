package com.agent.orchestrator.analytics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NodePerformanceMetrics.
 * Verifies basic functionality as per roadmap_01.md success criteria.
 */
class NodePerformanceMetricsTest {

    @Test
    void shouldCreateWithCorrectNodeId() {
        NodePerformanceMetrics metrics = new NodePerformanceMetrics("node-123");
        assertEquals("node-123", metrics.getNodeId());
    }

    @Test
    void shouldIncrementUtilization() {
        NodePerformanceMetrics metrics = new NodePerformanceMetrics("node-1");
        assertEquals(0, metrics.getUtilizationCount());
        
        metrics.incrementUtilization();
        assertEquals(1, metrics.getUtilizationCount());
        
        metrics.incrementUtilization();
        assertEquals(2, metrics.getUtilizationCount());
    }

    @Test
    void shouldRecordSuccess() {
        NodePerformanceMetrics metrics = new NodePerformanceMetrics("node-1");
        assertEquals(0, metrics.getSuccessCount());
        assertEquals(0, metrics.getExecutionCount());
        
        metrics.recordSuccess(100);
        assertEquals(1, metrics.getSuccessCount());
        assertEquals(1, metrics.getExecutionCount());
        assertEquals(100.0, metrics.getAverageExecutionTimeMs(), 0.01);
        
        metrics.recordSuccess(200);
        assertEquals(2, metrics.getSuccessCount());
        assertEquals(2, metrics.getExecutionCount());
        assertEquals(150.0, metrics.getAverageExecutionTimeMs(), 0.01);
    }

    @Test
    void shouldRecordFailure() {
        NodePerformanceMetrics metrics = new NodePerformanceMetrics("node-1");
        assertEquals(0, metrics.getFailureCount());
        
        metrics.recordFailure();
        assertEquals(1, metrics.getFailureCount());
        assertEquals(1, metrics.getExecutionCount());
        
        metrics.recordFailure();
        assertEquals(2, metrics.getFailureCount());
        assertEquals(2, metrics.getExecutionCount());
    }

    @Test
    void shouldCalculateSuccessRate() {
        NodePerformanceMetrics metrics = new NodePerformanceMetrics("node-1");
        
        // No executions yet
        assertEquals(0.0, metrics.getSuccessRate(), 0.001);
        
        // 1 success, 0 failures
        metrics.recordSuccess(100);
        assertEquals(1.0, metrics.getSuccessRate(), 0.001);
        
        // 1 success, 1 failure
        metrics.recordFailure();
        assertEquals(0.5, metrics.getSuccessRate(), 0.001);
        
        // 2 successes, 1 failure
        metrics.recordSuccess(150);
        assertEquals(2.0/3.0, metrics.getSuccessRate(), 0.001);
    }

    @Test
    void shouldRecordExecutionTimeSeparately() {
        NodePerformanceMetrics metrics = new NodePerformanceMetrics("node-1");
        
        metrics.recordExecutionTime(100);
        assertEquals(1, metrics.getExecutionCount());
        assertEquals(100.0, metrics.getAverageExecutionTimeMs(), 0.01);
        
        metrics.recordExecutionTime(200);
        assertEquals(2, metrics.getExecutionCount());
        assertEquals(150.0, metrics.getAverageExecutionTimeMs(), 0.01);
    }

    @Test
    void shouldUpdateLastExecutionTimestamp() {
        NodePerformanceMetrics metrics = new NodePerformanceMetrics("node-1");
        assertNull(metrics.getLastExecutionTimestamp());
        
        metrics.recordSuccess(100);
        assertNotNull(metrics.getLastExecutionTimestamp());
        
        metrics.recordFailure();
        assertNotNull(metrics.getLastExecutionTimestamp());
    }

    @Test
    void shouldCalculateUtilizationRate() {
        NodePerformanceMetrics metrics = new NodePerformanceMetrics("node-1");
        
        // No utilization yet
        assertEquals(0.0, metrics.getUtilizationRate(), 0.001);
        
        metrics.incrementUtilization();
        metrics.recordSuccess(100);
        assertEquals(1.0, metrics.getUtilizationRate(), 0.001);
        
        // 2 utilizations, 1 execution
        metrics.incrementUtilization();
        assertEquals(0.5, metrics.getUtilizationRate(), 0.001);
    }
}
