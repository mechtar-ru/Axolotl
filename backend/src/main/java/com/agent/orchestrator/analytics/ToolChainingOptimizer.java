package com.agent.orchestrator.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Optimizes tool chaining by learning which sequences are most effective.
 * 
 * Integration Points:
 * - ToolSynthesizer: Provides sequences to analyze for synthesis
 * - SandboxedExecutor: Tests recommended tool chains
 * - Tool usage tracking: Feeds execution data into recommendations
 * 
 * Scoring:
 * - Success rate weighted by execution time
 * - Context-based recommendations
 */

public class ToolChainingOptimizer {
    private static final Logger logger = LoggerFactory.getLogger(ToolChainingOptimizer.class);
    
    private final Map<String, List<SequenceOutcome>> sequenceHistory = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Double>> recommendations = new ConcurrentHashMap<>();
    
    private double successWeight = 0.7;
    private double speedWeight = 0.3;
    
    public ToolChainingOptimizer() {}
    
    public void recordSequenceOutcome(String context, List<String> sequence, 
                                    boolean success, long executionTimeMs) {
        SequenceOutcome outcome = new SequenceOutcome(sequence, success, executionTimeMs);
        sequenceHistory.computeIfAbsent(context, k -> new ArrayList<>()).add(outcome);
        
        logger.debug("Recorded outcome for {}: success={}, time={}ms", 
            sequence, success, executionTimeMs);
        
        updateRecommendations(context);
    }
    
    private void updateRecommendations(String context) {
        List<SequenceOutcome> outcomes = sequenceHistory.get(context);
        if (outcomes == null) return;
        
        Map<String, Double> scores = new HashMap<>();
        for (SequenceOutcome outcome : outcomes) {
            String key = sequenceKey(outcome.getSequence());
            double score = calculateEffectivenessScore(outcome);
            
            scores.compute(key, (k, v) -> v == null ? score : (v + score) / 2.0);
        }
        
        recommendations.put(context, scores);
    }
    
    public double calculateEffectivenessScore(SequenceOutcome outcome) {
        double successScore = outcome.isSuccess() ? 1.0 : 0.0;
        double speedScore = 1.0 / (1.0 + outcome.getExecutionTimeMs() / 1000.0);
        
        return successWeight * successScore + speedWeight * speedScore;
    }
    
    public List<List<String>> getTopSequences(String context, int limit) {
        Map<String, Double> scores = recommendations.get(context);
        if (scores == null) return Collections.emptyList();
        
        return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(limit)
            .map(e -> parseSequenceKey(e.getKey()))
            .collect(Collectors.toList());
    }
    
    public List<String> getRecommendedSequence(String context) {
        List<List<String>> top = getTopSequences(context, 1);
        return top.isEmpty() ? null : top.get(0);
    }
    
    public void adaptRecommendations(String context, List<String> actualSequence, 
                                     boolean improved, double improvementFactor) {
        SequenceOutcome outcome = new SequenceOutcome(actualSequence, improved, 0);
        outcome.setImprovementFactor(improvementFactor);
        
        sequenceHistory.computeIfAbsent(context, k -> new ArrayList<>()).add(outcome);
        updateRecommendations(context);
    }
    
    public double getSequenceSuccessRate(String context, List<String> sequence) {
        List<SequenceOutcome> outcomes = sequenceHistory.get(context);
        if (outcomes == null) return 0.0;
        
        String key = sequenceKey(sequence);
        long successCount = outcomes.stream()
            .filter(o -> sequenceKey(o.getSequence()).equals(key))
            .filter(SequenceOutcome::isSuccess)
            .count();
        
        long totalCount = outcomes.stream()
            .filter(o -> sequenceKey(o.getSequence()).equals(key))
            .count();
        
        return totalCount > 0 ? (double) successCount / totalCount : 0.0;
    }
    
    public double getAverageExecutionTime(String context, List<String> sequence) {
        List<SequenceOutcome> outcomes = sequenceHistory.get(context);
        if (outcomes == null) return 0.0;
        
        String key = sequenceKey(sequence);
        return outcomes.stream()
            .filter(o -> sequenceKey(o.getSequence()).equals(key))
            .mapToLong(SequenceOutcome::getExecutionTimeMs)
            .average()
            .orElse(0.0);
    }
    
    private String sequenceKey(List<String> sequence) {
        return String.join("->", sequence);
    }
    
    private List<String> parseSequenceKey(String key) {
        return Arrays.asList(key.split("->"));
    }
    
    public double getSuccessWeight() { return successWeight; }
    public void setSuccessWeight(double weight) { this.successWeight = weight; }
    public double getSpeedWeight() { return speedWeight; }
    public void setSpeedWeight(double weight) { this.speedWeight = weight; }
    
    public static class SequenceOutcome {
        private final List<String> sequence;
        private final boolean success;
        private final long executionTimeMs;
        private double improvementFactor;
        
        public SequenceOutcome(List<String> sequence, boolean success, long executionTimeMs) {
            this.sequence = new ArrayList<>(sequence);
            this.success = success;
            this.executionTimeMs = executionTimeMs;
            this.improvementFactor = 1.0;
        }
        
        public List<String> getSequence() { return new ArrayList<>(sequence); }
        public boolean isSuccess() { return success; }
        public long getExecutionTimeMs() { return executionTimeMs; }
        public double getImprovementFactor() { return improvementFactor; }
        public void setImprovementFactor(double factor) { this.improvementFactor = factor; }
    }
}