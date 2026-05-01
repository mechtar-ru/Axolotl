package com.agent.orchestrator.analytics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ModelPerformanceMonitor.
 */
class ModelPerformanceMonitorTest {

    @Test
    void shouldTrackRecentSuccessRate() {
        ModelPerformanceMonitor monitor = new ModelPerformanceMonitor(10, 0.2);
        
        monitor.recordExecution(TaskType.REASONING, "claude-3", true, 5000);
        monitor.recordExecution(TaskType.REASONING, "claude-3", true, 5000);
        monitor.recordExecution(TaskType.REASONING, "claude-3", false, 3000);
        
        double rate = monitor.getRecentSuccessRate(TaskType.REASONING, "claude-3");
        assertEquals(2.0 / 3.0, rate, 0.01);
    }

    @Test
    void shouldDetectPerformanceDrift() {
        ModelPerformanceMonitor monitor = new ModelPerformanceMonitor(10, 0.1);
        
        // Record good history (100% success)
        for (int i = 0; i < 10; i++) {
            monitor.recordExecution(TaskType.TEXT_GENERATION, "gpt-4", true, 2000);
        }
        
        // Record recent failures (0% success) - exceeds 10% threshold
        for (int i = 0; i < 5; i++) {
            monitor.recordExecution(TaskType.TEXT_GENERATION, "gpt-4", false, 2000);
        }
        
        assertTrue(monitor.hasPerformanceDrift(TaskType.TEXT_GENERATION, "gpt-4"));
    }

    @Test
    void shouldGetPerformanceTrend() {
        ModelPerformanceMonitor monitor = new ModelPerformanceMonitor(20, 0.2);
        
        // First half: failures
        for (int i = 0; i < 5; i++) {
            monitor.recordExecution(TaskType.SUMMARIZATION, "claude-3", false, 5000);
        }
        
        // Second half: success
        for (int i = 0; i < 5; i++) {
            monitor.recordExecution(TaskType.SUMMARIZATION, "claude-3", true, 3000);
        }
        
        assertEquals(ModelPerformanceMonitor.PerformanceTrend.IMPROVING, 
            monitor.getPerformanceTrend(TaskType.SUMMARIZATION, "claude-3"));
    }

    @Test
    void shouldHandleUnknownModel() {
        ModelPerformanceMonitor monitor = new ModelPerformanceMonitor();
        
        assertFalse(monitor.hasPerformanceDrift(TaskType.REASONING, "unknown-model"));
        assertEquals(0.0, monitor.getRecentSuccessRate(TaskType.REASONING, "unknown-model"));
    }

    @Test
    void shouldTrackAverageLatency() {
        ModelPerformanceMonitor monitor = new ModelPerformanceMonitor(10, 0.2);
        
        monitor.recordExecution(TaskType.CODE_GENERATION, "claude-3", true, 3000);
        monitor.recordExecution(TaskType.CODE_GENERATION, "claude-3", true, 5000);
        
        double avgLatency = monitor.getRecentAverageLatency(TaskType.CODE_GENERATION, "claude-3");
        assertEquals(4000.0, avgLatency, 100);
    }
}