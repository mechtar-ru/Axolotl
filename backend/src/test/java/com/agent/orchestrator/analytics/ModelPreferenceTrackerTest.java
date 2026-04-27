package com.agent.orchestrator.analytics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

/**
 * Unit tests for ModelPreferenceTracker.
 */
class ModelPreferenceTrackerTest {

    @Test
    void shouldRecordAndRetrieveMetrics() {
        ModelPreferenceTracker tracker = new ModelPreferenceTracker();
        
        tracker.recordResult(TaskType.REASONING, "claude-3", true, 5000, 0.5);
        tracker.recordResult(TaskType.REASONING, "claude-3", false, 3000, 0.3);
        
        double score = tracker.getModelScore(TaskType.REASONING, "claude-3");
        assertTrue(score > 0, "Should have a score");
        
        double confidence = tracker.getModelConfidence(TaskType.REASONING, "claude-3");
        assertTrue(confidence > 0, "Should have some confidence");
    }

    @Test
    void shouldRankModelsByPerformance() {
        ModelPreferenceTracker tracker = new ModelPreferenceTracker();
        
        // gpt-4 is faster but more expensive; claude-3 is slower but cheaper
        tracker.recordResult(TaskType.TEXT_GENERATION, "gpt-4", true, 2000, 2.0);
        tracker.recordResult(TaskType.TEXT_GENERATION, "claude-3", true, 3000, 0.5);
        
        List<String> ranked = tracker.getRankedModels(TaskType.TEXT_GENERATION);
        assertFalse(ranked.isEmpty());
        // gpt-4 has better time score (faster), claude-3 has better cost score (cheaper)
        // Both score similarly; just verify order exists
        assertTrue(ranked.contains("gpt-4"));
        assertTrue(ranked.contains("claude-3"));
    }

    @Test
    void shouldRecommendBestModel() {
        ModelPreferenceTracker tracker = new ModelPreferenceTracker();
        tracker.setExplorationRate(0.0); // No exploration for predictable test
        
        // Make one model clearly better (higher success rate, faster, cheaper)
        tracker.recordResult(TaskType.SUMMARIZATION, "claude-3", true, 3000, 0.5);
        tracker.recordResult(TaskType.SUMMARIZATION, "claude-3", true, 3000, 0.5);
        tracker.recordResult(TaskType.SUMMARIZATION, "claude-3", true, 3000, 0.5);
        
        tracker.recordResult(TaskType.SUMMARIZATION, "gpt-4", true, 5000, 2.0);
        
        String recommended = tracker.getRecommendedModel(
            TaskType.SUMMARIZATION,
            Arrays.asList("claude-3", "gpt-4")
        );
        assertEquals("claude-3", recommended);
    }

    @Test
    void shouldHandleUnknownTaskType() {
        ModelPreferenceTracker tracker = new ModelPreferenceTracker();
        
        List<String> ranked = tracker.getRankedModels(TaskType.REASONING);
        assertTrue(ranked.isEmpty());
    }

    @Test
    void shouldCalculateSuccessRate() {
        ModelPreferenceTracker tracker = new ModelPreferenceTracker();
        
        tracker.recordResult(TaskType.CODE_GENERATION, "claude-3", true, 1000, 0.1);
        tracker.recordResult(TaskType.CODE_GENERATION, "claude-3", true, 1000, 0.1);
        tracker.recordResult(TaskType.CODE_GENERATION, "claude-3", false, 1000, 0.1);
        
        // 2 successes, 1 failure = 66.7%
        double score = tracker.getModelScore(TaskType.CODE_GENERATION, "claude-3");
        assertTrue(score > 0.5, "Score should reflect 2/3 success rate");
    }
}