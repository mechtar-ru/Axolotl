package com.agent.orchestrator.analytics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NodeUsageTrackingSystem.
 */
class NodeUsageTrackingSystemTest {

    @Test
    void shouldTrackNodeStart() {
        NodeUsageTrackingSystem tracker = new NodeUsageTrackingSystem();
        tracker.recordNodeStart("node-1");
        
        NodePerformanceMetrics metrics = tracker.getMetrics("node-1");
        assertNotNull(metrics);
        assertEquals(1, metrics.getUtilizationCount());
    }

    @Test
    void shouldTrackNodeSuccess() {
        NodeUsageTrackingSystem tracker = new NodeUsageTrackingSystem();
        tracker.recordNodeSuccess("node-1", 150);
        
        NodePerformanceMetrics metrics = tracker.getMetrics("node-1");
        assertNotNull(metrics);
        assertEquals(1, metrics.getSuccessCount());
        assertEquals(150.0, metrics.getAverageExecutionTimeMs(), 0.01);
    }

    @Test
    void shouldTrackNodeFailure() {
        NodeUsageTrackingSystem tracker = new NodeUsageTrackingSystem();
        tracker.recordNodeFailure("node-1");
        
        NodePerformanceMetrics metrics = tracker.getMetrics("node-1");
        assertNotNull(metrics);
        assertEquals(1, metrics.getFailureCount());
    }

    @Test
    void shouldTrackExecutionTime() {
        NodeUsageTrackingSystem tracker = new NodeUsageTrackingSystem();
        tracker.recordExecutionTime("node-1", 200);
        
        NodePerformanceMetrics metrics = tracker.getMetrics("node-1");
        assertNotNull(metrics);
        assertEquals(200.0, metrics.getAverageExecutionTimeMs(), 0.01);
    }

    @Test
    void shouldGetAllMetrics() {
        NodeUsageTrackingSystem tracker = new NodeUsageTrackingSystem();
        tracker.recordNodeStart("node-1");
        tracker.recordNodeSuccess("node-1", 100);
        tracker.recordNodeStart("node-2");
        
        var allMetrics = tracker.getAllMetrics();
        assertEquals(2, allMetrics.size());
        assertTrue(allMetrics.containsKey("node-1"));
        assertTrue(allMetrics.containsKey("node-2"));
    }

    @Test
    void shouldClearMetrics() {
        NodeUsageTrackingSystem tracker = new NodeUsageTrackingSystem();
        tracker.recordNodeStart("node-1");
        tracker.clear();
        
        var allMetrics = tracker.getAllMetrics();
        assertTrue(allMetrics.isEmpty());
    }

    @Test
    void shouldCreateMetricsIfAbsent() {
        NodeUsageTrackingSystem tracker = new NodeUsageTrackingSystem();
        
        // Getting metrics for non-existent node should return null
        assertNull(tracker.getMetrics("non-existent"));
        
        // But recording should create it
        tracker.recordNodeStart("new-node");
        assertNotNull(tracker.getMetrics("new-node"));
    }
}
