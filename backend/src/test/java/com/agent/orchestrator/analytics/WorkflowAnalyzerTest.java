package com.agent.orchestrator.analytics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

/**
 * Unit tests for WorkflowAnalyzer.
 * Verifies analysis functionality as per roadmap_02.md success criteria.
 */
class WorkflowAnalyzerTest {

    @Test
    void shouldFindUnderutilizedNodes() {
        Map<String, NodePerformanceMetrics> metricsMap = new HashMap<>();
        
        // Node with 1% utilization (underutilized: 1 execution / 100 utilizations)
        NodePerformanceMetrics node1 = new NodePerformanceMetrics("node-1");
        for (int i = 0; i < 100; i++) {
            node1.incrementUtilization();
        }
        node1.recordSuccess(100); // 1 execution = 1% rate
        metricsMap.put("node-1", node1);
        
        // Node with 100% utilization (not underutilized)
        NodePerformanceMetrics node2 = new NodePerformanceMetrics("node-2");
        node2.incrementUtilization(); // 1 utilization
        node2.recordSuccess(100); // 1 execution = 100% rate
        metricsMap.put("node-2", node2);
        
        WorkflowAnalyzer analyzer = new WorkflowAnalyzer();
        List<String> underutilized = analyzer.findUnderutilizedNodes(metricsMap);
        
        assertEquals(1, underutilized.size());
        assertTrue(underutilized.contains("node-1"));
        assertFalse(underutilized.contains("node-2"));
    }

    @Test
    void shouldCalculateUtilizationRates() {
        Map<String, NodePerformanceMetrics> metricsMap = new HashMap<>();
        
        NodePerformanceMetrics node1 = new NodePerformanceMetrics("node-1");
        node1.incrementUtilization();
        node1.incrementUtilization(); // 2 utilizations
        node1.recordSuccess(100); // 1 execution = 50% rate
        metricsMap.put("node-1", node1);
        
        WorkflowAnalyzer analyzer = new WorkflowAnalyzer();
        Map<String, Double> rates = analyzer.calculateUtilizationRates(metricsMap);
        
        assertEquals(0.5, rates.get("node-1"), 0.001);
    }

    @Test
    void shouldGenerateRecommendations() {
        Map<String, NodePerformanceMetrics> metricsMap = new HashMap<>();
        
        NodePerformanceMetrics node1 = new NodePerformanceMetrics("node-1");
        node1.incrementUtilization();
        node1.incrementUtilization(); // 2 utilizations
        node1.recordSuccess(100); // 1 execution = 50% rate (under 5% threshold? No, 50% > 5%)
        metricsMap.put("node-1", node1);
        
        // Actually, let me create a truly underutilized node
        NodePerformanceMetrics node2 = new NodePerformanceMetrics("node-2");
        for (int i = 0; i < 100; i++) {
            node2.incrementUtilization(); // 100 utilizations
        }
        node2.recordSuccess(100); // 1 execution = 1% rate (under 5%)
        metricsMap.put("node-2", node2);
        
        WorkflowAnalyzer analyzer = new WorkflowAnalyzer();
        List<String> recommendations = analyzer.generateRecommendations(metricsMap);
        
        assertFalse(recommendations.isEmpty());
        assertTrue(recommendations.get(0).contains("node-2"));
    }

    @Test
    void shouldRespectCustomThreshold() {
        Map<String, NodePerformanceMetrics> metricsMap = new HashMap<>();
        
        NodePerformanceMetrics node1 = new NodePerformanceMetrics("node-1");
        for (int i = 0; i < 10; i++) {
            node1.incrementUtilization(); // 10 utilizations
        }
        node1.recordSuccess(100); // 1 execution = 10% rate
        metricsMap.put("node-1", node1);
        
        // With 5% threshold, node-1 (10%) should NOT be underutilized
        WorkflowAnalyzer analyzer1 = new WorkflowAnalyzer(0.05);
        List<String> underutilized1 = analyzer1.findUnderutilizedNodes(metricsMap);
        assertTrue(underutilized1.isEmpty());
        
        // With 15% threshold, node-1 (10%) SHOULD be underutilized
        WorkflowAnalyzer analyzer2 = new WorkflowAnalyzer(0.15);
        List<String> underutilized2 = analyzer2.findUnderutilizedNodes(metricsMap);
        assertEquals(1, underutilized2.size());
        assertTrue(underutilized2.contains("node-1"));
    }
}
