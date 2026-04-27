package com.agent.orchestrator.analytics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

/**
 * Unit tests for PreferenceLearner.
 */
class PreferenceLearnerTest {

    @Test
    void shouldRecordUserChoice() {
        PreferenceLearner learner = new PreferenceLearner();
        
        PreferenceLearner.PreferenceContext context = new PreferenceLearner.PreferenceContext(
            "reasoning", "agent", "general", null
        );
        
        learner.recordUserChoice("user-1", context, "claude-3");
        
        PreferenceLearner.PredictionResult result = learner.predictPreference("user-1", context);
        
        assertEquals("claude-3", result.getPredictedChoice());
    }
    
    @Test
    void shouldPredictPreference() {
        PreferenceLearner learner = new PreferenceLearner(2);
        
        PreferenceLearner.PreferenceContext context = new PreferenceLearner.PreferenceContext(
            "reasoning", "source", "general", null
        );
        
        learner.recordUserChoice("user-1", context, "claude-3");
        learner.recordUserChoice("user-1", context, "claude-3");
        
        PreferenceLearner.PredictionResult result = learner.predictPreference("user-1", context);
        
        assertEquals("claude-3", result.getPredictedChoice());
    }
    
    @Test
    void shouldReturnNullForUnknownUser() {
        PreferenceLearner learner = new PreferenceLearner();
        
        PreferenceLearner.PreferenceContext context = new PreferenceLearner.PreferenceContext(
            "reasoning", "agent", "general", null
        );
        
        PreferenceLearner.PredictionResult result = learner.predictPreference("unknown-user", context);
        
        assertNull(result.getPredictedChoice());
        assertEquals(0.0, result.getConfidence(), 0.01);
    }
    
    @Test
    void shouldGetConfidence() {
        PreferenceLearner learner = new PreferenceLearner(2);
        
        PreferenceLearner.PreferenceContext context = new PreferenceLearner.PreferenceContext(
            "reasoning", "source", "general", null
        );
        
        learner.recordUserChoice("user-1", context, "claude-3");
        learner.recordUserChoice("user-1", context, "claude-3");
        
        double confidence = learner.getConfidence("user-1", context);
        
        assertTrue(confidence > 0.0);
    }
    
    @Test
    void shouldReturnAlternatives() {
        PreferenceLearner learner = new PreferenceLearner(2);
        
        PreferenceLearner.PreferenceContext context = new PreferenceLearner.PreferenceContext(
            "reasoning", "source", "general", null
        );
        
        learner.recordUserChoice("user-1", context, "claude-3");
        learner.recordUserChoice("user-1", context, "claude-3");
        learner.recordUserChoice("user-1", context, "gpt-4");
        
        PreferenceLearner.PredictionResult result = learner.predictPreference("user-1", context);
        
        assertTrue(result.getAlternatives().contains("claude-3"));
        assertTrue(result.getAlternatives().contains("gpt-4"));
    }
    
    @Test
    void shouldClearPreferences() {
        PreferenceLearner learner = new PreferenceLearner();
        
        PreferenceLearner.PreferenceContext context = new PreferenceLearner.PreferenceContext(
            "reasoning", "agent", "general", null
        );
        
        learner.recordUserChoice("user-1", context, "claude-3");
        learner.clearPreferences("user-1");
        
        PreferenceLearner.PredictionResult result = learner.predictPreference("user-1", context);
        
        assertNull(result.getPredictedChoice());
    }
    
    @Test
    void shouldLearnFromMultipleContexts() {
        PreferenceLearner learner = new PreferenceLearner();
        
        PreferenceLearner.PreferenceContext reasoningContext = new PreferenceLearner.PreferenceContext(
            "reasoning", "source", "general", null
        );
        PreferenceLearner.PreferenceContext creativeContext = new PreferenceLearner.PreferenceContext(
            "creative", "source", "general", null
        );
        
        learner.recordUserChoice("user-1", reasoningContext, "claude-3");
        learner.recordUserChoice("user-1", creativeContext, "gpt-4");
        
        PreferenceLearner.PredictionResult reasoningResult = learner.predictPreference("user-1", reasoningContext);
        PreferenceLearner.PredictionResult creativeResult = learner.predictPreference("user-1", creativeContext);
        
        assertEquals("claude-3", reasoningResult.getPredictedChoice());
        assertEquals("gpt-4", creativeResult.getPredictedChoice());
    }
    
    @Test
    void shouldHandleNullInputs() {
        PreferenceLearner learner = new PreferenceLearner();
        
        learner.recordUserChoice(null, null, null);
        
        // Should not throw
    }
    
    @Test
    void shouldGetPreferences() {
        PreferenceLearner learner = new PreferenceLearner(1);
        
        PreferenceLearner.PreferenceContext context = new PreferenceLearner.PreferenceContext(
            "reasoning", "source", "general", null
        );
        
        learner.recordUserChoice("user-1", context, "claude-3");
        
        List<PreferenceLearner.UserPreference> prefs = learner.getPreferences("user-1");
        
        assertFalse(prefs.isEmpty());
    }
}