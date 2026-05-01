package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.WorkflowSchema;
import java.util.*;

/**
 * Classifies workflows into task types based on structure and node composition.
 * Uses rule-based classification with confidence scoring.
 * 
 * Integration point: Used by EnsembleModelRouter to determine which models are best suited for a workflow.
 */


public class TaskTypeClassifier {

    /**
     * Classification result with task type and confidence score.
     */
    public static class ClassificationResult {
        private final TaskType taskType;
        private final double confidence;

        public ClassificationResult(TaskType taskType, double confidence) {
            this.taskType = taskType;
            this.confidence = confidence;
        }

        public TaskType getTaskType() {
            return taskType;
        }

        public double getConfidence() {
            return confidence;
        }
    }

    /**
     * Classifies a workflow into a task type.
     * 
     * @param workflow the workflow to classify
     * @return classification result with task type and confidence
     */
    public ClassificationResult classify(WorkflowSchema workflow) {
        if (workflow == null || workflow.getNodes() == null || workflow.getNodes().isEmpty()) {
            return new ClassificationResult(TaskType.DATA_PROCESSING, 0.3);
        }

        List<Node> nodes = workflow.getNodes();
        Map<String, Integer> nodeTypeCounts = countNodeTypes(nodes);
        
        int agentCount = nodeTypeCounts.getOrDefault("agent", 0);
        int sourceCount = nodeTypeCounts.getOrDefault("source", 0);
        int outputCount = nodeTypeCounts.getOrDefault("output", 0);
        int conditionCount = nodeTypeCounts.getOrDefault("condition", 0);
        int loopCount = nodeTypeCounts.getOrDefault("loop", 0);

        // Classification rules with confidence scoring
        if (agentCount >= 3) {
            return new ClassificationResult(TaskType.REASONING, 0.85);
        }

        if (conditionCount > 0 || loopCount > 0) {
            return new ClassificationResult(TaskType.DECISION_MAKING, 0.8);
        }

        if (agentCount >= 1 && sourceCount >= 1 && outputCount >= 1) {
            return new ClassificationResult(TaskType.QUESTION_ANSWERING, 0.75);
        }

        if (agentCount == 1 && sourceCount >= 1) {
            Node agentNode = findFirstNodeByType(nodes, "agent");
            if (agentNode != null && agentNode.getData() != null) {
                String prompt = agentNode.getData().getSystemPrompt();
                if (prompt != null) {
                    String lowerPrompt = prompt.toLowerCase();
                    if (lowerPrompt.contains("summarize") || lowerPrompt.contains("summary")) {
                        return new ClassificationResult(TaskType.SUMMARIZATION, 0.9);
                    }
                    if (lowerPrompt.contains("translate")) {
                        return new ClassificationResult(TaskType.TRANSLATION, 0.9);
                    }
                    if (lowerPrompt.contains("code") || lowerPrompt.contains("program")) {
                        return new ClassificationResult(TaskType.CODE_GENERATION, 0.85);
                    }
                }
            }
            return new ClassificationResult(TaskType.TEXT_GENERATION, 0.7);
        }

        if (sourceCount > 0 && outputCount > 0 && agentCount == 0) {
            return new ClassificationResult(TaskType.DATA_PROCESSING, 0.6);
        }

        return new ClassificationResult(TaskType.TEXT_GENERATION, 0.5);
    }

    /**
     * Classifies a workflow and returns all task types with confidence scores.
     * 
     * @param workflow the workflow to classify
     * @return map of task types to confidence scores
     */
    public Map<TaskType, Double> classifyWithScores(WorkflowSchema workflow) {
        ClassificationResult primary = classify(workflow);
        Map<TaskType, Double> scores = new HashMap<>();
        scores.put(primary.getTaskType(), primary.getConfidence());
        return scores;
    }

    private Map<String, Integer> countNodeTypes(List<Node> nodes) {
        Map<String, Integer> counts = new HashMap<>();
        for (Node node : nodes) {
            String type = node.getType() != null ? node.getType() : "unknown";
            counts.merge(type, 1, Integer::sum);
        }
        return counts;
    }

    private Node findFirstNodeByType(List<Node> nodes, String type) {
        return nodes.stream()
                .filter(n -> type.equals(n.getType()))
                .findFirst()
                .orElse(null);
    }
}
