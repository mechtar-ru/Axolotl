package com.agent.orchestrator.analytics;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Estimates cognitive load from user interaction patterns with the workflow canvas.
 * 
 * Interaction patterns that indicate high cognitive load:
 * - High frequency of undo/redo actions
 * - Repeated node deletions and recreations
 * - Long hesitation times between actions
 * - Rapid zoom/pan changes (indicates confusion)
 * - Frequent menu navigation without action
 */


public class CognitiveLoadEstimator {
    private static final double DEFAULT_HESITATION_THRESHOLD_MS = 3000;
    private static final int RECENT_INTERACTIONS_WINDOW = 20;
    
    private final Queue<InteractionEvent> recentInteractions;
    private double hesitationThresholdMs;
    private String userId;
    
    public CognitiveLoadEstimator() {
        this.recentInteractions = new ConcurrentLinkedQueue<>();
        this.hesitationThresholdMs = DEFAULT_HESITATION_THRESHOLD_MS;
    }
    
    public CognitiveLoadEstimator(String userId) {
        this();
        this.userId = userId;
    }
    
    /**
     * Records a user interaction event.
     */
    public void recordInteraction(String eventType, String context) {
        InteractionEvent event = new InteractionEvent(eventType, System.currentTimeMillis(), context);
        recentInteractions.offer(event);
        
        // Keep only recent interactions
        while (recentInteractions.size() > RECENT_INTERACTIONS_WINDOW) {
            recentInteractions.poll();
        }
    }
    
    /**
     * Estimates cognitive load based on recent interaction patterns.
     * Returns a score between 0 (low load) and 1 (high load).
     */
    public double estimateCognitiveLoad() {
        List<InteractionEvent> events = new ArrayList<>(recentInteractions);
        if (events.isEmpty()) {
            return 0.0;
        }
        
        double score = 0.0;
        
        // Factor 1: Undo/redo frequency (high = confused)
        long undoRedoCount = events.stream()
            .filter(e -> e.eventType.equals("undo") || e.eventType.equals("redo"))
            .count();
        double undoRedoRatio = (double) undoRedoCount / events.size();
        score += undoRedoRatio * 0.3;
        
        // Factor 2: Node deletion/recreation (high = trial and error)
        long deleteRecreateCount = events.stream()
            .filter(e -> e.eventType.equals("node_delete") || e.eventType.equals("node_create"))
            .count();
        double deleteRecreateRatio = Math.min(1.0, deleteRecreateCount / 10.0);
        score += deleteRecreateRatio * 0.25;
        
        // Factor 3: Hesitation times (long pauses between actions)
        double hesitationScore = computeHesitationScore(events);
        score += hesitationScore * 0.25;
        
        // Factor 4: Rapid zoom/pan (indicates searching/confusion)
        long zoomPanCount = events.stream()
            .filter(e -> e.eventType.equals("zoom") || e.eventType.equals("pan"))
            .count();
        double zoomPanRatio = Math.min(1.0, zoomPanCount / 8.0);
        score += zoomPanRatio * 0.2;
        
        return Math.min(1.0, score);
    }
    
    /**
     * Returns current cognitive load level as a category.
     */
    public LoadLevel getCurrentLoadLevel() {
        double score = estimateCognitiveLoad();
        if (score < 0.3) return LoadLevel.LOW;
        if (score < 0.6) return LoadLevel.MEDIUM;
        return LoadLevel.HIGH;
    }
    
    /**
     * Gets the raw cognitive load score (0-1).
     */
    public double getCognitiveLoadScore() {
        return estimateCognitiveLoad();
    }
    
    /**
     * Clears interaction history for a fresh start.
     */
    public void reset() {
        recentInteractions.clear();
    }
    
    /**
     * Sets the hesitation threshold in milliseconds.
     */
    public void setHesitationThresholdMs(double thresholdMs) {
        this.hesitationThresholdMs = thresholdMs;
    }
    
    public double getHesitationThresholdMs() {
        return hesitationThresholdMs;
    }
    
    public String getUserId() {
        return userId;
    }
    
    private double computeHesitationScore(List<InteractionEvent> events) {
        if (events.size() < 2) return 0.0;
        
        int hesitationCount = 0;
        List<InteractionEvent> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparingLong(e -> e.timestamp));
        
        for (int i = 1; i < sorted.size(); i++) {
            long gap = sorted.get(i).timestamp - sorted.get(i - 1).timestamp;
            if (gap > hesitationThresholdMs) {
                hesitationCount++;
            }
        }
        
        return Math.min(1.0, hesitationCount / 5.0);
    }
    
    /**
     * Represents a user interaction event.
     */
    public static class InteractionEvent {
        public final String eventType;
        public final long timestamp;
        public final String context;
        
        public InteractionEvent(String eventType, long timestamp, String context) {
            this.eventType = eventType;
            this.timestamp = timestamp;
            this.context = context;
        }
    }
    
    /**
     * Cognitive load level categories.
     */
    public enum LoadLevel {
        LOW, MEDIUM, HIGH
    }
}
