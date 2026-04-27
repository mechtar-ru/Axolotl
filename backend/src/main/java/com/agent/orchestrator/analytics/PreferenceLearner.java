package com.agent.orchestrator.analytics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Learns individual user preferences from workflow creation and execution patterns.
 * 
 * Integration Points:
 * - WorkflowCanvas: record node selections and layouts chosen by users
 * - EnsembleModelRouter: provide personalized model suggestions
 * - UINodeSuggestionService: provide personalized node suggestions
 * - UI: use predicted preferences for pre-selecting options
 * 
 * Preferences tracked:
 * - Preferred node types per context
 * - Preferred models per task type
 * - Preferred layouts
 * - Interaction patterns (e.g., node sequence patterns)
 */


public class PreferenceLearner {
    
    public static class UserPreference {
        private final String userId;
        private final String context;
        private final String choice;
        private final int count;
        private final double confidence;
        
        public UserPreference(String userId, String context, String choice, 
                          int count, double confidence) {
            this.userId = userId;
            this.context = context;
            this.choice = choice;
            this.count = count;
            this.confidence = confidence;
        }
        
        public String getUserId() { return userId; }
        public String getContext() { return context; }
        public String getChoice() { return choice; }
        public int getCount() { return count; }
        public double getConfidence() { return confidence; }
    }
    
    public static class PredictionResult {
        private final String predictedChoice;
        private final double confidence;
        private final List<String> alternatives;
        
        public PredictionResult(String predictedChoice, double confidence, List<String> alternatives) {
            this.predictedChoice = predictedChoice;
            this.confidence = confidence;
            this.alternatives = alternatives;
        }
        
        public String getPredictedChoice() { return predictedChoice; }
        public double getConfidence() { return confidence; }
        public List<String> getAlternatives() { return alternatives; }
    }
    
    public static class PreferenceContext {
        private final String taskType;
        private final String previousNodeType;
        private final String workflowType;
        private final Map<String, Object> additionalData;
        
        public PreferenceContext(String taskType, String previousNodeType, 
                               String workflowType, Map<String, Object> additionalData) {
            this.taskType = taskType;
            this.previousNodeType = previousNodeType;
            this.workflowType = workflowType;
            this.additionalData = additionalData != null ? additionalData : new HashMap<>();
        }
        
        public String getTaskType() { return taskType; }
        public String getPreviousNodeType() { return previousNodeType; }
        public String getWorkflowType() { return workflowType; }
        public Map<String, Object> getAdditionalData() { return additionalData; }
    }
    
    private final ConcurrentHashMap<String, Map<String, Map<String, Integer>>> userPreferences;
    private final ConcurrentHashMap<String, Map<String, Set<String>>> userPatternSequences;
    private final int minCountForConfidence;
    
    public PreferenceLearner() {
        this(3);
    }
    
    public PreferenceLearner(int minCountForConfidence) {
        this.userPreferences = new ConcurrentHashMap<>();
        this.userPatternSequences = new ConcurrentHashMap<>();
        this.minCountForConfidence = minCountForConfidence;
    }
    
    /**
     * Records a user choice in a specific context.
     */
    public void recordUserChoice(String userId, PreferenceContext context, String choice) {
        if (userId == null || context == null || choice == null) {
            return;
        }
        
        String contextKey = buildContextKey(context);
        
        userPreferences.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(contextKey, k -> new ConcurrentHashMap<>())
            .merge(choice, 1, Integer::sum);
        
        recordPatternSequence(userId, context, choice);
    }
    
    /**
     * Records a pattern sequence for the user.
     */
    private void recordPatternSequence(String userId, PreferenceContext context, String choice) {
        String sequenceKey = "sequence:" + context.getTaskType() + ":" + context.getPreviousNodeType();
        
        userPatternSequences.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(sequenceKey, k -> new HashSet<>())
            .add(choice);
    }
    
    /**
     * Builds a context key from preference context.
     */
    private String buildContextKey(PreferenceContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append(context.getTaskType()).append(":");
        sb.append(context.getPreviousNodeType()).append(":");
        sb.append(context.getWorkflowType());
        return sb.toString();
    }
    
    /**
     * Updates preference models (called periodically).
     */
    public void updatePreferences(String userId) {
        Map<String, Map<String, Integer>> prefs = userPreferences.get(userId);
        if (prefs == null) {
            return;
        }
        
        for (Map<String, Integer> contextPrefs : prefs.values()) {
            int total = contextPrefs.values().stream().mapToInt(Integer::intValue).sum();
            if (total >= minCountForConfidence) {
                // Preferences are strong enough
            }
        }
    }
    
    /**
     * Predicts user preference for a given context.
     */
    public PredictionResult predictPreference(String userId, PreferenceContext context) {
        Map<String, Map<String, Integer>> prefs = userPreferences.get(userId);
        if (prefs == null) {
            return new PredictionResult(null, 0.0, Collections.emptyList());
        }
        
        String contextKey = buildContextKey(context);
        Map<String, Integer> contextPrefs = prefs.get(contextKey);
        
        if (contextPrefs == null || contextPrefs.isEmpty()) {
            return new PredictionResult(null, 0.0, Collections.emptyList());
        }
        
        int total = contextPrefs.values().stream().mapToInt(Integer::intValue).sum();
        
        String predictedChoice = null;
        int maxCount = 0;
        List<String> sortedChoices = new ArrayList<>();
        
        for (Map.Entry<String, Integer> entry : contextPrefs.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                predictedChoice = entry.getKey();
            }
            sortedChoices.add(entry.getKey());
        }
        
        sortedChoices.sort((a, b) -> {
            int countA = contextPrefs.getOrDefault(a, 0);
            int countB = contextPrefs.getOrDefault(b, 0);
            return Integer.compare(countB, countA);
        });
        
        double confidence = calculateConfidence(total, maxCount);
        
        return new PredictionResult(predictedChoice, confidence, sortedChoices);
    }
    
    /**
     * Calculates confidence score based on counts.
     */
    private double calculateConfidence(int total, int maxCount) {
        if (total < minCountForConfidence) {
            return 0.0;
        }
        
        double frequency = (double) maxCount / total;
        double thresholdFactor = Math.min(1.0, (double) total / minCountForConfidence);
        
        return frequency * thresholdFactor;
    }
    
    /**
     * Gets confidence in prediction for a context.
     */
    public double getConfidence(String userId, PreferenceContext context) {
        PredictionResult result = predictPreference(userId, context);
        return result.getConfidence();
    }
    
    /**
     * Gets all preferences for a user.
     */
    public List<UserPreference> getPreferences(String userId) {
        Map<String, Map<String, Integer>> prefs = userPreferences.get(userId);
        if (prefs == null) {
            return Collections.emptyList();
        }
        
        List<UserPreference> result = new ArrayList<>();
        
        for (Map.Entry<String, Map<String, Integer>> contextEntry : prefs.entrySet()) {
            String contextKey = contextEntry.getKey();
            Map<String, Integer> choices = contextEntry.getValue();
            
            int total = choices.values().stream().mapToInt(Integer::intValue).sum();
            
            for (Map.Entry<String, Integer> choiceEntry : choices.entrySet()) {
                double confidence = calculateConfidence(total, choiceEntry.getValue());
                result.add(new UserPreference(userId, contextKey, choiceEntry.getKey(),
                    choiceEntry.getValue(), confidence));
            }
        }
        
        return result;
    }
    
    /**
     * Clears preferences for a user.
     */
    public void clearPreferences(String userId) {
        userPreferences.remove(userId);
        userPatternSequences.remove(userId);
    }
    
    /**
     * Gets suggested nodes based on user history.
     */
    public List<String> getSuggestedNodeTypes(String userId, String taskType) {
        PreferenceContext context = new PreferenceContext(taskType, null, "general", null);
        PredictionResult result = predictPreference(userId, context);
        
        if (result.getPredictedChoice() != null) {
            return result.getAlternatives();
        }
        
        return Collections.emptyList();
    }
    
    /**
     * Gets suggested models based on user history.
     */
    public String getSuggestedModel(String userId, String taskType) {
        PreferenceContext context = new PreferenceContext(taskType, "agent", "general", null);
        PredictionResult result = predictPreference(userId, context);
        
        return result.getPredictedChoice();
    }
}