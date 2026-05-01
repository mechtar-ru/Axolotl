package com.agent.orchestrator.analytics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CognitiveLoadEstimatorTest {
    
    @Test
    void testEmptyInteractionsReturnsZeroLoad() {
        CognitiveLoadEstimator estimator = new CognitiveLoadEstimator();
        assertEquals(0.0, estimator.estimateCognitiveLoad(), 0.001);
        assertEquals(CognitiveLoadEstimator.LoadLevel.LOW, estimator.getCurrentLoadLevel());
    }
    
    @Test
    void testLowLoadForSimpleInteractions() {
        CognitiveLoadEstimator estimator = new CognitiveLoadEstimator();
        
        // Simulate normal workflow creation
        estimator.recordInteraction("node_create", "source");
        estimator.recordInteraction("node_create", "agent");
        estimator.recordInteraction("edge_create", "source->agent");
        estimator.recordInteraction("node_create", "output");
        estimator.recordInteraction("edge_create", "agent->output");
        
        double score = estimator.estimateCognitiveLoad();
        assertTrue(score < 0.3, "Simple interactions should result in low load, got: " + score);
        assertEquals(CognitiveLoadEstimator.LoadLevel.LOW, estimator.getCurrentLoadLevel());
    }
    
    @Test
    void testHighLoadForUndoRedo() {
        CognitiveLoadEstimator estimator = new CognitiveLoadEstimator();
        
        // Simulate confused user with lots of undo/redo
        for (int i = 0; i < 10; i++) {
            estimator.recordInteraction("undo", "canvas");
            estimator.recordInteraction("redo", "canvas");
        }
        estimator.recordInteraction("node_create", "agent");
        
        double score = estimator.estimateCognitiveLoad();
        assertTrue(score > 0.2, "Frequent undo/redo should increase load, got: " + score);
    }
    
    @Test
    void testHighLoadForDeleteRecreate() {
        CognitiveLoadEstimator estimator = new CognitiveLoadEstimator();
        
        // Simulate trial and error with nodes
        for (int i = 0; i < 5; i++) {
            estimator.recordInteraction("node_create", "agent");
            estimator.recordInteraction("node_delete", "agent");
        }
        
        double score = estimator.estimateCognitiveLoad();
        assertTrue(score > 0.2, "Frequent delete/recreate should increase load, got: " + score);
    }
    
    @Test
    void testHesitationDetection() throws InterruptedException {
        CognitiveLoadEstimator estimator = new CognitiveLoadEstimator();
        estimator.setHesitationThresholdMs(100); // Low threshold for testing
        
        estimator.recordInteraction("node_create", "source");
        Thread.sleep(150); // Hesitation
        estimator.recordInteraction("node_create", "agent");
        
        double score = estimator.estimateCognitiveLoad();
        assertTrue(score > 0.0, "Hesitation should contribute to load score");
    }
    
    @Test
    void testZoomPanIncreasesLoad() {
        CognitiveLoadEstimator estimator = new CognitiveLoadEstimator();
        
        // Normal creation
        estimator.recordInteraction("node_create", "source");
        estimator.recordInteraction("node_create", "agent");
        
        // Lots of zoom/pan (searching for something)
        for (int i = 0; i < 10; i++) {
            estimator.recordInteraction("zoom", "canvas");
            estimator.recordInteraction("pan", "canvas");
        }
        
        double score = estimator.estimateCognitiveLoad();
        assertTrue(score >= 0.2, "Excessive zoom/pan should increase load, got: " + score);
    }
    
    @Test
    void testResetClearsHistory() {
        CognitiveLoadEstimator estimator = new CognitiveLoadEstimator();
        
        estimator.recordInteraction("undo", "canvas");
        estimator.recordInteraction("undo", "canvas");
        assertTrue(estimator.estimateCognitiveLoad() > 0.0);
        
        estimator.reset();
        assertEquals(0.0, estimator.estimateCognitiveLoad(), 0.001);
        assertEquals(CognitiveLoadEstimator.LoadLevel.LOW, estimator.getCurrentLoadLevel());
    }
    
    @Test
    void testGetCognitiveLoadScore() {
        CognitiveLoadEstimator estimator = new CognitiveLoadEstimator();
        estimator.recordInteraction("node_create", "source");
        
        double score = estimator.getCognitiveLoadScore();
        assertTrue(score >= 0.0 && score <= 1.0, "Score should be normalized 0-1");
    }
    
    @Test
    void testUserId() {
        CognitiveLoadEstimator estimator = new CognitiveLoadEstimator("user123");
        assertEquals("user123", estimator.getUserId());
    }
    
    @Test
    void testLoadLevelTransitions() {
        CognitiveLoadEstimator estimator = new CognitiveLoadEstimator();
        
        // Initially low
        assertEquals(CognitiveLoadEstimator.LoadLevel.LOW, estimator.getCurrentLoadLevel());
        
        // Add some stress
        for (int i = 0; i < 5; i++) {
            estimator.recordInteraction("undo", "canvas");
        }
        assertEquals(CognitiveLoadEstimator.LoadLevel.MEDIUM, estimator.getCurrentLoadLevel());
    }
}
