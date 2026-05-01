package com.agent.orchestrator.analytics;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Detects optimal human intervention points during workflow execution.
 * 
 * Monitors execution traces for:
 * - Low confidence in AI decisions
 * - Workflow stagnation (no progress)
 * - Repeated errors or recovery attempts
 * - High cognitive load combined with uncertainty
 */


public class InterventionDetector {
    private static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.6;
    private static final long DEFAULT_STAGNATION_MS = 30000; // 30 seconds
    private static final int DEFAULT_ERROR_REPEAT_THRESHOLD = 3;
    
    private final Queue<ExecutionEvent> executionTrace;
    private double confidenceThreshold;
    private long stagnationThresholdMs;
    private int errorRepeatThreshold;
    
    public InterventionDetector() {
        this.executionTrace = new ConcurrentLinkedQueue<>();
        this.confidenceThreshold = DEFAULT_CONFIDENCE_THRESHOLD;
        this.stagnationThresholdMs = DEFAULT_STAGNATION_MS;
        this.errorRepeatThreshold = DEFAULT_ERROR_REPEAT_THRESHOLD;
    }
    
    /**
     * Records an execution event (node execution, decision, etc.).
     */
    public void recordExecutionEvent(String nodeId, String eventType, double confidence, String details) {
        ExecutionEvent event = new ExecutionEvent(nodeId, eventType, confidence, System.currentTimeMillis(), details);
        executionTrace.offer(event);
        
        // Keep recent events only
        while (executionTrace.size() > 100) {
            executionTrace.poll();
        }
    }
    
    /**
     * Detects if intervention is needed based on current execution state.
     * Returns intervention score (0-1, higher = more needed).
     */
    public double detectInterventionNeed() {
        List<ExecutionEvent> events = new ArrayList<>(executionTrace);
        if (events.isEmpty()) {
            return 0.0;
        }
        
        double score = 0.0;
        
        // Factor 1: Low confidence decisions
        double confidenceScore = computeLowConfidenceScore(events);
        score += confidenceScore * 0.4;
        
        // Factor 2: Workflow stagnation
        double stagnationScore = computeStagnationScore(events);
        score += stagnationScore * 0.3;
        
        // Factor 3: Repeated errors
        double errorScore = computeRepeatedErrorScore(events);
        score += errorScore * 0.3;
        
        return Math.min(1.0, score);
    }
    
    /**
     * Checks if intervention is recommended.
     */
    public boolean isInterventionRecommended() {
        return detectInterventionNeed() > 0.5;
    }
    
    /**
     * Gets the reason for intervention recommendation.
     */
    public List<String> getInterventionReasons() {
        List<String> reasons = new ArrayList<>();
        List<ExecutionEvent> events = new ArrayList<>(executionTrace);
        
        if (hasLowConfidenceDecision(events)) {
            reasons.add("Low confidence in AI decision (confidence < " + confidenceThreshold + ")");
        }
        if (isWorkflowStagnant(events)) {
            reasons.add("Workflow stagnation detected (no progress for " + (stagnationThresholdMs / 1000) + "s)");
        }
        if (hasRepeatedErrors(events)) {
            reasons.add("Repeated errors detected (" + errorRepeatThreshold + " or more occurrences)");
        }
        
        return reasons;
    }
    
    /**
     * Clears execution trace.
     */
    public void reset() {
        executionTrace.clear();
    }
    
    // Getters and setters
    
    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }
    
    public void setConfidenceThreshold(double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }
    
    public long getStagnationThresholdMs() {
        return stagnationThresholdMs;
    }
    
    public void setStagnationThresholdMs(long stagnationThresholdMs) {
        this.stagnationThresholdMs = stagnationThresholdMs;
    }
    
    public int getErrorRepeatThreshold() {
        return errorRepeatThreshold;
    }
    
    public void setErrorRepeatThreshold(int errorRepeatThreshold) {
        this.errorRepeatThreshold = errorRepeatThreshold;
    }
    
    // Private helper methods
    
    private double computeLowConfidenceScore(List<ExecutionEvent> events) {
        if (events.isEmpty()) return 0.0;
        
        long lowConfidenceCount = events.stream()
            .filter(e -> e.confidence < confidenceThreshold)
            .count();
        
        return Math.min(1.0, (double) lowConfidenceCount / 5.0);
    }
    
    private double computeStagnationScore(List<ExecutionEvent> events) {
        if (events.size() < 2) return 0.0;
        
        // Sort by timestamp
        List<ExecutionEvent> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparingLong(e -> e.timestamp));
        
        long lastEventTime = sorted.get(sorted.size() - 1).timestamp;
        long timeSinceLastEvent = System.currentTimeMillis() - lastEventTime;
        
        if (timeSinceLastEvent > stagnationThresholdMs) {
            return 1.0;
        }
        
        return timeSinceLastEvent / (double) stagnationThresholdMs;
    }
    
    private double computeRepeatedErrorScore(List<ExecutionEvent> events) {
        // Count errors by node
        Map<String, Integer> errorCountByNode = new HashMap<>();
        
        for (ExecutionEvent event : events) {
            if ("error".equals(event.eventType) || "failure".equals(event.eventType)) {
                errorCountByNode.merge(event.nodeId, 1, Integer::sum);
            }
        }
        
        // Find max repeat count
        int maxRepeats = errorCountByNode.values().stream()
            .max(Integer::compareTo)
            .orElse(0);
        
        if (maxRepeats >= errorRepeatThreshold) {
            return 1.0;
        }
        
        return maxRepeats / (double) errorRepeatThreshold;
    }
    
    private boolean hasLowConfidenceDecision(List<ExecutionEvent> events) {
        return events.stream().anyMatch(e -> e.confidence < confidenceThreshold);
    }
    
    private boolean isWorkflowStagnant(List<ExecutionEvent> events) {
        if (events.isEmpty()) return false;
        
        List<ExecutionEvent> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparingLong(e -> e.timestamp));
        
        long lastEventTime = sorted.get(sorted.size() - 1).timestamp;
        return (System.currentTimeMillis() - lastEventTime) > stagnationThresholdMs;
    }
    
    private boolean hasRepeatedErrors(List<ExecutionEvent> events) {
        Map<String, Integer> errorCountByNode = new HashMap<>();
        
        for (ExecutionEvent event : events) {
            if ("error".equals(event.eventType) || "failure".equals(event.eventType)) {
                errorCountByNode.merge(event.nodeId, 1, Integer::sum);
            }
        }
        
        return errorCountByNode.values().stream().anyMatch(count -> count >= errorRepeatThreshold);
    }
    
    /**
     * Represents an execution event during workflow run.
     */
    public static class ExecutionEvent {
        public final String nodeId;
        public final String eventType;
        public final double confidence;
        public final long timestamp;
        public final String details;
        
        public ExecutionEvent(String nodeId, String eventType, double confidence, long timestamp, String details) {
            this.nodeId = nodeId;
            this.eventType = eventType;
            this.confidence = confidence;
            this.timestamp = timestamp;
            this.details = details;
        }
    }
}
