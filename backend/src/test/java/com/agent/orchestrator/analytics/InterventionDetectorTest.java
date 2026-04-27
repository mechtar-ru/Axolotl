package com.agent.orchestrator.analytics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

public class InterventionDetectorTest {
    
    @Test
    void testEmptyTraceNoInterventionNeeded() {
        InterventionDetector detector = new InterventionDetector();
        assertEquals(0.0, detector.detectInterventionNeed(), 0.001);
        assertFalse(detector.isInterventionRecommended());
    }
    
    @Test
    void testLowConfidenceDetection() {
        InterventionDetector detector = new InterventionDetector();
        detector.setConfidenceThreshold(0.6);
        
        // Record low confidence decisions (need 5+ for significant score)
        detector.recordExecutionEvent("node1", "decision", 0.3, "model selection");
        detector.recordExecutionEvent("node2", "decision", 0.4, "routing");
        detector.recordExecutionEvent("node3", "decision", 0.5, "output");
        detector.recordExecutionEvent("node4", "decision", 0.2, "choice");
        detector.recordExecutionEvent("node5", "decision", 0.3, "selection");
        
        double score = detector.detectInterventionNeed();
        assertTrue(score > 0.0, "Low confidence should trigger intervention, got: " + score);
        
        // Check reasons
        assertTrue(detector.getInterventionReasons().stream()
            .anyMatch(r -> r.contains("Low confidence")));
    }
    
    @Test
    void testHighConfidenceNoIntervention() {
        InterventionDetector detector = new InterventionDetector();
        
        // Record high confidence decisions
        detector.recordExecutionEvent("node1", "decision", 0.9, "model selection");
        detector.recordExecutionEvent("node2", "decision", 0.95, "routing");
        detector.recordExecutionEvent("node3", "success", 0.85, "output");
        
        assertFalse(detector.isInterventionRecommended());
    }
    
    @Test
    void testWorkflowStagnationDetection() throws InterruptedException {
        InterventionDetector detector = new InterventionDetector();
        detector.setStagnationThresholdMs(100); // Low threshold for testing
        
        // Need at least 2 events for stagnation calculation
        detector.recordExecutionEvent("node1", "start", 0.8, "started");
        detector.recordExecutionEvent("node2", "processing", 0.7, "working");
        Thread.sleep(150); // Stagnation
        
        double score = detector.detectInterventionNeed();
        assertTrue(score > 0.0, "Stagnation should trigger intervention, got: " + score);
        assertTrue(detector.getInterventionReasons().stream()
            .anyMatch(r -> r.contains("stagnation")));
    }
    
    @Test
    void testRepeatedErrorDetection() {
        InterventionDetector detector = new InterventionDetector();
        detector.setErrorRepeatThreshold(3);
        
        // Repeat errors on same node
        for (int i = 0; i < 5; i++) {
            detector.recordExecutionEvent("node1", "error", 0.0, "connection failed");
        }
        
        double score = detector.detectInterventionNeed();
        assertTrue(score > 0.0, "Repeated errors should trigger intervention, got: " + score);
        assertTrue(detector.isInterventionRecommended());
        assertTrue(detector.getInterventionReasons().stream()
            .anyMatch(r -> r.contains("Repeated errors")));
    }
    
    @Test
    void testCombinedFactorsIncreaseScore() {
        InterventionDetector detector = new InterventionDetector();
        
        // Low confidence
        detector.recordExecutionEvent("node1", "decision", 0.3, "uncertain");
        // Stagnation (simulate old timestamp)
        detector.recordExecutionEvent("node2", "start", 0.5, "stuck");
        // Repeated errors
        for (int i = 0; i < 3; i++) {
            detector.recordExecutionEvent("node3", "error", 0.0, "failed");
        }
        
        double score = detector.detectInterventionNeed();
        assertTrue(score > 0.5, "Combined factors should highly recommend intervention, got: " + score);
    }
    
    @Test
    void testResetClearsTrace() {
        InterventionDetector detector = new InterventionDetector();
        
        detector.recordExecutionEvent("node1", "error", 0.0, "failed");
        assertTrue(detector.detectInterventionNeed() > 0.0);
        
        detector.reset();
        assertEquals(0.0, detector.detectInterventionNeed(), 0.001);
        assertTrue(detector.getInterventionReasons().isEmpty());
    }
    
    @Test
    void testGetInterventionReasonsEmptyWhenNoIssues() {
        InterventionDetector detector = new InterventionDetector();
        detector.recordExecutionEvent("node1", "success", 0.9, "completed");
        
        List<String> reasons = detector.getInterventionReasons();
        assertTrue(reasons.isEmpty(), "No reasons when no issues");
    }
    
    @Test
    void testConfidenceThresholdSetting() {
        InterventionDetector detector = new InterventionDetector();
        assertEquals(0.6, detector.getConfidenceThreshold(), 0.001);
        
        detector.setConfidenceThreshold(0.8);
        assertEquals(0.8, detector.getConfidenceThreshold(), 0.001);
    }
    
    @Test
    void testStagnationThresholdSetting() {
        InterventionDetector detector = new InterventionDetector();
        assertEquals(30000, detector.getStagnationThresholdMs());
        
        detector.setStagnationThresholdMs(60000);
        assertEquals(60000, detector.getStagnationThresholdMs());
    }
    
    @Test
    void testErrorRepeatThresholdSetting() {
        InterventionDetector detector = new InterventionDetector();
        assertEquals(3, detector.getErrorRepeatThreshold());
        
        detector.setErrorRepeatThreshold(5);
        assertEquals(5, detector.getErrorRepeatThreshold());
    }
}
