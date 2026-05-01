package com.agent.orchestrator.analytics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * Captures reasoning traces for AI decisions during workflow execution.
 * 
 * Integration Points:
 * - EnsembleModelRouter: trace model selection decisions
 * - NodeFactory: trace node creation decisions
 * - Workflow execution: trace routing decisions
 * - ExplanationGenerator: provide traces for generating explanations
 * 
 * Decision types tracked:
 * - MODEL_SELECTION: model selection during routing
 * - NODE_SELECTION: node creation/selection during workflow
 * - ROUTING_DECISION: workflow path decisions
 * - NODE_SKIP: node skipping decisions
 */


public class ReasoningTraceAnalyzer {
    
    public static class DecisionTrace {
        private final String executionId;
        private final String decisionId;
        private final String decisionType;
        private final Map<String, Object> inputs;
        private final Map<String, Double> alternativeScores;
        private final String chosenAlternative;
        private final long timestamp;
        private final Map<String, Object> metadata;
        
        public DecisionTrace(String executionId, String decisionId, String decisionType,
                         Map<String, Object> inputs, Map<String, Double> alternativeScores,
                         String chosenAlternative, Map<String, Object> metadata) {
            this.executionId = executionId;
            this.decisionId = decisionId;
            this.decisionType = decisionType;
            this.inputs = inputs != null ? inputs : new HashMap<>();
            this.alternativeScores = alternativeScores != null ? alternativeScores : new HashMap<>();
            this.chosenAlternative = chosenAlternative;
            this.timestamp = System.currentTimeMillis();
            this.metadata = metadata != null ? metadata : new HashMap<>();
        }
        
        public String getExecutionId() { return executionId; }
        public String getDecisionId() { return decisionId; }
        public String getDecisionType() { return decisionType; }
        public Map<String, Object> getInputs() { return inputs; }
        public Map<String, Double> getAlternativeScores() { return alternativeScores; }
        public String getChosenAlternative() { return chosenAlternative; }
        public long getTimestamp() { return timestamp; }
        public Map<String, Object> getMetadata() { return metadata; }
    }
    
    private boolean enabled;
    private double samplingRate;
    private final ConcurrentHashMap<String, List<DecisionTrace>> executionTraces;
    private final ConcurrentHashMap<String, Queue<DecisionTrace>> activeTraces;
    
    public ReasoningTraceAnalyzer() {
        this.enabled = true;
        this.samplingRate = 1.0;
        this.executionTraces = new ConcurrentHashMap<>();
        this.activeTraces = new ConcurrentHashMap<>();
    }
    
    /**
     * Traces a decision point during workflow execution.
     */
    public void traceDecision(String executionId, String decisionType, Map<String, Object> inputs,
                       Map<String, Double> alternativeScores, String chosenAlternative) {
        traceDecision(executionId, decisionType, inputs, alternativeScores, chosenAlternative, null);
    }
    
    /**
     * Traces a decision point with metadata.
     */
    public void traceDecision(String executionId, String decisionType, Map<String, Object> inputs,
                       Map<String, Double> alternativeScores, String chosenAlternative,
                       Map<String, Object> metadata) {
        if (!enabled) {
            return;
        }
        
        if (Math.random() > samplingRate) {
            return;
        }
        
        String decisionId = decisionType + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        
        DecisionTrace trace = new DecisionTrace(
            executionId, decisionId, decisionType,
            inputs, alternativeScores, chosenAlternative, metadata
        );
        
        executionTraces.computeIfAbsent(executionId, k -> Collections.synchronizedList(new ArrayList<>())).add(trace);
    }
    
    /**
     * Starts a new execution trace session.
     */
    public void startExecution(String executionId) {
        activeTraces.put(executionId, new ConcurrentLinkedQueue<>());
    }
    
    /**
     * Ends an execution trace session.
     */
    public void endExecution(String executionId) {
        Queue<DecisionTrace> traces = activeTraces.remove(executionId);
        if (traces != null && !traces.isEmpty()) {
            executionTraces.putIfAbsent(executionId, Collections.synchronizedList(new ArrayList<>()));
        }
    }
    
    /**
     * Gets all traces for a workflow execution.
     */
    public List<DecisionTrace> getTracesForExecution(String executionId) {
        List<DecisionTrace> traces = executionTraces.get(executionId);
        if (traces == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(traces);
    }
    
    /**
     * Gets traces filtered by type.
     */
    public List<DecisionTrace> getTracesByType(String executionId, String decisionType) {
        return getTracesForExecution(executionId).stream()
            .filter(t -> decisionType.equals(t.getDecisionType()))
            .collect(Collectors.toList());
    }
    
    /**
     * Clears traces for an execution.
     */
    public void clearTraces(String executionId) {
        executionTraces.remove(executionId);
    }
    
    /**
     * Clears all traces.
     */
    public void clearAllTraces() {
        executionTraces.clear();
        activeTraces.clear();
    }
    
    /**
     * Gets trace summary.
     */
    public Map<String, Object> getTraceSummary(String executionId) {
        List<DecisionTrace> traces = getTracesForExecution(executionId);
        
        Map<String, Object> summary = new HashMap<>();
        summary.put("executionId", executionId);
        summary.put("totalTraces", traces.size());
        
        Map<String, Long> byType = traces.stream()
            .collect(Collectors.groupingBy(DecisionTrace::getDecisionType, Collectors.counting()));
        summary.put("byType", byType);
        
        return summary;
    }
    
    /**
     * Enables/disables tracing.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    /**
     * Checks if tracing is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Sets sampling rate (0.0 to 1.0).
     */
    public void setSamplingRate(double rate) {
        this.samplingRate = Math.max(0.0, Math.min(1.0, rate));
    }
    
    /**
     * Gets sampling rate.
     */
    public double getSamplingRate() {
        return samplingRate;
    }
    
    /**
     * Gets total trace count across all executions.
     */
    public int getTotalTraceCount() {
        return executionTraces.values().stream()
            .mapToInt(List::size)
            .sum();
    }
}