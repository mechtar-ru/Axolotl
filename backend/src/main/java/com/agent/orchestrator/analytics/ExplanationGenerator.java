package com.agent.orchestrator.analytics;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates natural language explanations for AI decisions in the workflow.
 * 
 * Integration Points:
 * - ReasoningTraceAnalyzer: get traces to generate explanations from decision data
 * - EnsembleModelRouter: explain why a model was selected over others
 * - NodeFactory: explain why a node was created
 * - UI: request explanations for display to users
 * 
 * Decision types supported:
 * - MODEL_SELECTION: explains why a specific model was chosen
 * - NODE_SELECTION: explains why a specific node was used
 * - ROUTING_DECISION: explains why a workflow path was taken
 * - NODE_SKIP: explains why a node was skipped
 */


public class ExplanationGenerator {
    
    public enum DecisionType {
        MODEL_SELECTION,
        NODE_SELECTION,
        ROUTING_DECISION,
        NODE_SKIP,
        WORKFLOW_STEP
    }
    
    public static class DecisionContext {
        private final DecisionType type;
        private final String targetId;
        private final String targetName;
        private final List<Alternative> alternatives;
        private final Map<String, Object> metadata;
        private final long timestamp;
        
        public DecisionContext(DecisionType type, String targetId, String targetName,
                          List<Alternative> alternatives, Map<String, Object> metadata) {
            this.type = type;
            this.targetId = targetId;
            this.targetName = targetName;
            this.alternatives = alternatives != null ? alternatives : Collections.emptyList();
            this.metadata = metadata != null ? metadata : new HashMap<>();
            this.timestamp = System.currentTimeMillis();
        }
        
        public DecisionType getType() { return type; }
        public String getTargetId() { return targetId; }
        public String getTargetName() { return targetName; }
        public List<Alternative> getAlternatives() { return alternatives; }
        public Map<String, Object> getMetadata() { return metadata; }
        public long getTimestamp() { return timestamp; }
    }
    
    public static class Alternative {
        private final String id;
        private final String name;
        private final double score;
        private final String reason;
        
        public Alternative(String id, String name, double score, String reason) {
            this.id = id;
            this.name = name;
            this.score = score;
            this.reason = reason;
        }
        
        public String getId() { return id; }
        public String getName() { return name; }
        public double getScore() { return score; }
        public String getReason() { return reason; }
    }
    
    private ReasoningTraceAnalyzer traceAnalyzer;
    private boolean useTraceAnalyzer;
    
    public ExplanationGenerator() {
        this(null);
    }
    
    public ExplanationGenerator(ReasoningTraceAnalyzer traceAnalyzer) {
        this.traceAnalyzer = traceAnalyzer;
        this.useTraceAnalyzer = (traceAnalyzer != null);
    }
    
    /**
     * Generates an explanation for a given decision context.
     */
    public String generateExplanation(DecisionContext context) {
        if (context == null) {
            return "No decision context provided.";
        }
        
        switch (context.getType()) {
            case MODEL_SELECTION:
                return explainModelSelection(context);
            case NODE_SELECTION:
                return explainNodeSelection(context);
            case ROUTING_DECISION:
                return explainRoutingDecision(context);
            case NODE_SKIP:
                return explainNodeSkip(context);
            case WORKFLOW_STEP:
                return explainWorkflowStep(context);
            default:
                return "Unknown decision type.";
        }
    }
    
    /**
     * Generates explanation for a model selection decision.
     */
    private String explainModelSelection(DecisionContext context) {
        List<Alternative> alternatives = context.getAlternatives();
        String selected = context.getTargetName();
        
        if (alternatives.isEmpty()) {
            return "Model '" + selected + "' was selected for this task.";
        }
        
        Alternative selectedAlt = null;
        double topScore = -1;
        for (Alternative alt : alternatives) {
            if (alt.getId().equals(context.getTargetId())) {
                selectedAlt = alt;
            }
            if (alt.getScore() > topScore) {
                topScore = alt.getScore();
            }
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("Model '").append(selected).append("' was selected because ");
        
        if (selectedAlt != null) {
            sb.append(String.format("it scored %.2f", selectedAlt.getScore()));
            
            if (selectedAlt.getScore() >= topScore * 0.9) {
                sb.append(" (highest among considered models)");
            }
            
            if (selectedAlt.getReason() != null && !selectedAlt.getReason().isEmpty()) {
                sb.append(". ").append(selectedAlt.getReason());
            }
        } else {
            sb.append("it was the best fit for the task.");
        }
        
        if (alternatives.size() > 1) {
            sb.append(" Other models considered: ");
            sb.append(alternatives.stream()
                .filter(a -> !a.getId().equals(context.getTargetId()))
                .map(Alternative::getName)
                .collect(Collectors.joining(", ")));
        }
        
        return sb.toString();
    }
    
    /**
     * Generates explanation for a node selection decision.
     */
    private String explainNodeSelection(DecisionContext context) {
        String nodeName = context.getTargetName();
        Map<String, Object> metadata = context.getMetadata();
        
        String taskType = (String) metadata.getOrDefault("taskType", "general");
        String inputType = (String) metadata.getOrDefault("inputType", "unknown");
        
        StringBuilder sb = new StringBuilder();
        sb.append("Node '").append(nodeName).append("' was selected ");
        
        if ("source".equals(inputType)) {
            sb.append("as the starting point for the workflow.");
        } else if ("agent".equals(context.getMetadata().get("nodeType"))) {
            sb.append("to process the input with ").append(taskType).append(" task type.");
        } else if ("output".equals(context.getMetadata().get("nodeType"))) {
            sb.append("to output the final result of the workflow.");
        } else {
            sb.append("based on the workflow structure.");
        }
        
        List<Alternative> alternatives = context.getAlternatives();
        if (!alternatives.isEmpty()) {
            sb.append(" Other options considered: ");
            sb.append(alternatives.stream()
                .map(Alternative::getName)
                .collect(Collectors.joining(", ")));
        }
        
        return sb.toString();
    }
    
    /**
     * Generates explanation for a routing decision.
     */
    private String explainRoutingDecision(DecisionContext context) {
        String targetName = context.getTargetName();
        Map<String, Object> metadata = context.getMetadata();
        
        Double confidence = (Double) metadata.get("confidence");
        String condition = (String) metadata.get("condition");
        
        StringBuilder sb = new StringBuilder();
        sb.append("Workflow took the path to '").append(targetName).append("' ");
        
        if (confidence != null) {
            sb.append("with ").append(String.format("%.0f%%", confidence * 100)).append(" confidence ");
        }
        
        if (condition != null) {
            sb.append("because '").append(condition).append("' condition was met.");
        } else {
            sb.append("as it was the optimal next step.");
        }
        
        return sb.toString();
    }
    
    /**
     * Generates explanation for why a node was skipped.
     */
    private String explainNodeSkip(DecisionContext context) {
        String nodeName = context.getTargetName();
        Map<String, Object> metadata = context.getMetadata();
        
        String reason = (String) metadata.get("skipReason");
        
        StringBuilder sb = new StringBuilder();
        sb.append("Node '").append(nodeName).append("' was skipped ");
        
        if (reason != null) {
            sb.append("because ").append(reason);
        } else {
            sb.append("as it was not needed for the current workflow execution.");
        }
        
        return sb.toString();
    }
    
    /**
     * Generates explanation for a workflow step.
     */
    private String explainWorkflowStep(DecisionContext context) {
        String stepName = context.getTargetName();
        Map<String, Object> metadata = context.getMetadata();
        
        String previousResult = (String) metadata.get("previousResult");
        String nextStep = (String) metadata.get("nextStep");
        
        StringBuilder sb = new StringBuilder();
        sb.append("Step '").append(stepName).append("' was executed ");
        
        if (previousResult != null) {
            sb.append("after processing the previous step's result.");
        } else {
            sb.append("as the next step in the workflow.");
        }
        
        if (nextStep != null) {
            sb.append(" Next step will be: ").append(nextStep);
        }
        
        return sb.toString();
    }
    
    /**
     * Gets explanations from trace analyzer for a workflow execution.
     */
    public List<String> getExplanationsFromTrace(String executionId) {
        if (traceAnalyzer == null) {
            return Collections.emptyList();
        }
        
        List<ReasoningTraceAnalyzer.DecisionTrace> traces = traceAnalyzer.getTracesForExecution(executionId);
        List<String> explanations = new ArrayList<>();
        
        for (ReasoningTraceAnalyzer.DecisionTrace trace : traces) {
            DecisionContext context = convertTraceToContext(trace);
            explanations.add(generateExplanation(context));
        }
        
        return explanations;
    }
    
    private DecisionContext convertTraceToContext(ReasoningTraceAnalyzer.DecisionTrace trace) {
        DecisionType type = DecisionType.MODEL_SELECTION;
        if ("NODE_SELECTION".equals(trace.getDecisionType())) {
            type = DecisionType.NODE_SELECTION;
        } else if ("ROUTING_DECISION".equals(trace.getDecisionType())) {
            type = DecisionType.ROUTING_DECISION;
        } else if ("NODE_SKIP".equals(trace.getDecisionType())) {
            type = DecisionType.NODE_SKIP;
        }
        
        List<Alternative> alternatives = new ArrayList<>();
        Map<String, Double> altScores = trace.getAlternativeScores();
        if (altScores != null) {
            for (Map.Entry<String, Double> entry : altScores.entrySet()) {
                alternatives.add(new Alternative(entry.getKey(), entry.getKey(), entry.getValue(), null));
            }
        }
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("executionId", trace.getExecutionId());
        
        return new DecisionContext(type, trace.getDecisionId(), trace.getDecisionId(),
            alternatives, metadata);
    }
    
    /**
     * Sets the trace analyzer for generating explanations from traces.
     */
    public void setTraceAnalyzer(ReasoningTraceAnalyzer traceAnalyzer) {
        this.traceAnalyzer = traceAnalyzer;
        this.useTraceAnalyzer = (traceAnalyzer != null);
    }
    
    /**
     * Checks if using trace analyzer.
     */
    public boolean isUsingTraceAnalyzer() {
        return useTraceAnalyzer;
    }
}