package com.agent.orchestrator.analytics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks model performance for each task type.
 * 
 * Integration: Records execution results from LLM calls.
 * Used by EnsembleModelRouter to select optimal models.
 */


public class ModelPreferenceTracker {
    private final Map<TaskType, Map<String, ModelMetrics>> metricsByTaskType = new ConcurrentHashMap<>();
    private double explorationRate = 0.1; // Epsilon-greedy: 10% chance to explore

    /**
     * Records an execution result for a model on a task type.
     */
    public void recordResult(TaskType taskType, String modelId, boolean success, long executionTimeMs, double cost) {
        metricsByTaskType
            .computeIfAbsent(taskType, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(modelId, k -> new ModelMetrics(modelId))
            .recordExecution(success, executionTimeMs, cost);
    }

    /**
     * Gets ranked model recommendations for a task type.
     * Uses epsilon-greedy exploration: returns random model with probability = explorationRate.
     */
    public String getRecommendedModel(TaskType taskType, List<String> availableModels) {
        if (availableModels == null || availableModels.isEmpty()) {
            return null;
        }

        // Exploration: try a random model occasionally
        if (Math.random() < explorationRate) {
            return availableModels.get((int) (Math.random() * availableModels.size()));
        }

        // Exploitation: return best model
        List<String> ranked = getRankedModels(taskType);
        for (String model : ranked) {
            if (availableModels.contains(model)) {
                return model;
            }
        }

        return availableModels.get(0);
    }

    /**
     * Gets models ranked by performance score for a task type.
     */
    public List<String> getRankedModels(TaskType taskType) {
        Map<String, ModelMetrics> models = metricsByTaskType.get(taskType);
        if (models == null || models.isEmpty()) {
            return new ArrayList<>();
        }

        return models.values().stream()
            .sorted((a, b) -> Double.compare(b.getPerformanceScore(), a.getPerformanceScore()))
            .map(ModelMetrics::getModelId)
            .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Gets performance score for a specific model on a task type.
     */
    public double getModelScore(TaskType taskType, String modelId) {
        Map<String, ModelMetrics> taskMetrics = metricsByTaskType.get(taskType);
        if (taskMetrics == null) return 0.0;
        ModelMetrics metrics = taskMetrics.get(modelId);
        return metrics != null ? metrics.getPerformanceScore() : 0.0;
    }

    /**
     * Gets confidence score (based on number of executions).
     */
    public double getModelConfidence(TaskType taskType, String modelId) {
        Map<String, ModelMetrics> taskMetrics = metricsByTaskType.get(taskType);
        if (taskMetrics == null) return 0.0;
        ModelMetrics metrics = taskMetrics.get(modelId);
        return metrics != null ? metrics.getConfidence() : 0.0;
    }

    public double getExplorationRate() { return explorationRate; }
    public void setExplorationRate(double rate) { this.explorationRate = rate; }

    /**
     * Metrics for a single model on a single task type.
     */
    public static class ModelMetrics {
        private final String modelId;
        private int successCount;
        private int totalCount;
        private long totalTimeMs;
        private double totalCost;

        public ModelMetrics(String modelId) {
            this.modelId = modelId;
        }

        public void recordExecution(boolean success, long timeMs, double cost) {
            totalCount++;
            if (success) successCount++;
            totalTimeMs += timeMs;
            totalCost += cost;
        }

        public String getModelId() { return modelId; }
        public int getSuccessCount() { return successCount; }
        public int getTotalCount() { return totalCount; }

        public double getSuccessRate() {
            return totalCount > 0 ? (double) successCount / totalCount : 0.0;
        }

        public double getAverageTimeMs() {
            return totalCount > 0 ? (double) totalTimeMs / totalCount : 0.0;
        }

        public double getPerformanceScore() {
            // Higher is better: high success rate, low time, low cost
            double successWeight = 0.6;
            double timeWeight = 0.2;
            double costWeight = 0.2;

            double successScore = getSuccessRate();
            double timeScore = totalCount > 0 ? 1.0 - Math.min(1.0, getAverageTimeMs() / 60000) : 0.0; // Normalize to 60s
            double costScore = 1.0 - Math.min(1.0, totalCost / 10.0); // Normalize to $10

            return successWeight * successScore + timeWeight * timeScore + costWeight * costScore;
        }

        public double getConfidence() {
            return Math.min(1.0, totalCount / 100.0); // 100 executions = full confidence
        }
    }
}