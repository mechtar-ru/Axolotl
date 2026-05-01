package com.agent.orchestrator.analytics;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ToolChainingOptimizerTest {
    
    @Test
    void testRecordSequenceOutcome() {
        ToolChainingOptimizer optimizer = new ToolChainingOptimizer();
        
        List<String> sequence = Arrays.asList("grep", "sort", "uniq");
        optimizer.recordSequenceOutcome("text-processing", sequence, true, 100);
        
        double successRate = optimizer.getSequenceSuccessRate("text-processing", sequence);
        assertEquals(1.0, successRate);
    }
    
    @Test
    void testCalculateEffectivenessScore() {
        ToolChainingOptimizer optimizer = new ToolChainingOptimizer();
        
        ToolChainingOptimizer.SequenceOutcome successOutcome = 
            new ToolChainingOptimizer.SequenceOutcome(
                Arrays.asList("cmd1", "cmd2"), true, 50);
        double successScore = optimizer.calculateEffectivenessScore(successOutcome);
        assertTrue(successScore > 0.5);
        
        ToolChainingOptimizer.SequenceOutcome failureOutcome = 
            new ToolChainingOptimizer.SequenceOutcome(
                Arrays.asList("cmd1", "cmd2"), false, 50);
        double failureScore = optimizer.calculateEffectivenessScore(failureOutcome);
        assertTrue(failureScore < successScore);
    }
    
    @Test
    void testGetRecommendedSequence() {
        ToolChainingOptimizer optimizer = new ToolChainingOptimizer();
        
        List<String> goodSequence = Arrays.asList("grep", "sort");
        optimizer.recordSequenceOutcome("search", goodSequence, true, 100);
        optimizer.recordSequenceOutcome("search", goodSequence, true, 100);
        
        List<String> badSequence = Arrays.asList("find", "xargs");
        optimizer.recordSequenceOutcome("search", badSequence, false, 500);
        
        List<String> recommended = optimizer.getRecommendedSequence("search");
        assertNotNull(recommended);
        assertEquals(goodSequence, recommended);
    }
    
    @Test
    void testGetTopSequences() {
        ToolChainingOptimizer optimizer = new ToolChainingOptimizer();
        
        List<String> seq1 = Arrays.asList("grep", "sort");
        List<String> seq2 = Arrays.asList("awk", "print");
        
        optimizer.recordSequenceOutcome("context", seq1, true, 100);
        optimizer.recordSequenceOutcome("context", seq1, true, 100);
        optimizer.recordSequenceOutcome("context", seq1, true, 100);
        
        optimizer.recordSequenceOutcome("context", seq2, true, 200);
        
        List<List<String>> top = optimizer.getTopSequences("context", 2);
        assertEquals(2, top.size());
        assertEquals(seq1, top.get(0));
    }
    
    @Test
    void testSequenceSuccessRate() {
        ToolChainingOptimizer optimizer = new ToolChainingOptimizer();
        
        List<String> sequence = Arrays.asList("a", "b");
        optimizer.recordSequenceOutcome("ctx", sequence, true, 100);
        optimizer.recordSequenceOutcome("ctx", sequence, false, 100);
        optimizer.recordSequenceOutcome("ctx", sequence, true, 100);
        
        double rate = optimizer.getSequenceSuccessRate("ctx", sequence);
        assertEquals(2.0 / 3.0, rate, 0.01);
    }
    
    @Test
    void testAverageExecutionTime() {
        ToolChainingOptimizer optimizer = new ToolChainingOptimizer();
        
        List<String> sequence = Arrays.asList("cmd1", "cmd2");
        optimizer.recordSequenceOutcome("ctx", sequence, true, 100);
        optimizer.recordSequenceOutcome("ctx", sequence, true, 200);
        
        double avg = optimizer.getAverageExecutionTime("ctx", sequence);
        assertEquals(150.0, avg, 0.01);
    }
    
    @Test
    void testAdaptRecommendations() {
        ToolChainingOptimizer optimizer = new ToolChainingOptimizer();
        
        List<String> sequence = Arrays.asList("optimized", "cmd");
        optimizer.adaptRecommendations("ctx", sequence, true, 1.5);
        
        List<String> recommended = optimizer.getRecommendedSequence("ctx");
        assertEquals(sequence, recommended);
    }
    
    @Test
    void testWeightConfiguration() {
        ToolChainingOptimizer optimizer = new ToolChainingOptimizer();
        
        optimizer.setSuccessWeight(0.9);
        optimizer.setSpeedWeight(0.1);
        
        assertEquals(0.9, optimizer.getSuccessWeight());
        assertEquals(0.1, optimizer.getSpeedWeight());
    }
    
    @Test
    void testSequenceOutcomeImprovementFactor() {
        ToolChainingOptimizer.SequenceOutcome outcome = 
            new ToolChainingOptimizer.SequenceOutcome(
                Arrays.asList("a", "b"), true, 100);
        
        assertEquals(1.0, outcome.getImprovementFactor());
        
        outcome.setImprovementFactor(2.5);
        assertEquals(2.5, outcome.getImprovementFactor());
    }
}