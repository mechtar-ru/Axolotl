package com.agent.orchestrator.analytics;

import java.util.*;

/**
 * Monitors model performance over time and detects performance drift.
 * 
 * Uses a sliding window to track recent performance and alerts when significant changes occur.
 */


public class ModelPerformanceMonitor {
    private final int windowSize;
    private final double driftThreshold;
    private final Map<TaskType, Map<String, ModelPerformanceHistory>> historyByTaskType = new HashMap<>();

    public ModelPerformanceMonitor() {
        this(20, 0.2); // 20 executions window, 20% drift threshold
    }

    public ModelPerformanceMonitor(int windowSize, double driftThreshold) {
        this.windowSize = windowSize;
        this.driftThreshold = driftThreshold;
    }

    /**
     * Records an execution result.
     */
    public void recordExecution(TaskType taskType, String modelId, boolean success, long executionTimeMs) {
        getHistory(taskType, modelId).record(success, executionTimeMs);
    }

    /**
     * Gets recent success rate (last N executions).
     */
    public double getRecentSuccessRate(TaskType taskType, String modelId) {
        return getHistory(taskType, modelId).getRecentSuccessRate(windowSize);
    }

    /**
     * Gets recent average latency.
     */
    public double getRecentAverageLatency(TaskType taskType, String modelId) {
        return getHistory(taskType, modelId).getRecentAverageLatency(windowSize);
    }

    /**
     * Detects performance drift (recent vs. lifetime performance).
     * Returns true if performance has changed significantly.
     */
    public boolean hasPerformanceDrift(TaskType taskType, String modelId) {
        Map<String, ModelPerformanceHistory> taskHistory = historyByTaskType.get(taskType);
        ModelPerformanceHistory history = taskHistory != null ? taskHistory.get(modelId) : null;
        if (history == null) return false;

        double recentSuccessRate = history.getRecentSuccessRate(windowSize);
        double lifetimeSuccessRate = history.getLifetimeSuccessRate();
        double drift = Math.abs(recentSuccessRate - lifetimeSuccessRate);

        return drift > driftThreshold;
    }

    /**
     * Gets drift magnitude (how much performance has changed).
     */
    public double getDriftMagnitude(TaskType taskType, String modelId) {
        Map<String, ModelPerformanceHistory> taskHistory = historyByTaskType.get(taskType);
        ModelPerformanceHistory history = taskHistory != null ? taskHistory.get(modelId) : null;
        if (history == null) return 0.0;

        double recentSuccessRate = history.getRecentSuccessRate(windowSize);
        double lifetimeSuccessRate = history.getLifetimeSuccessRate();
        return recentSuccessRate - lifetimeSuccessRate;
    }

    /**
     * Gets model reliability (based on consistency).
     */
    public double getModelReliability(TaskType taskType, String modelId) {
        Map<String, ModelPerformanceHistory> taskHistory = historyByTaskType.get(taskType);
        ModelPerformanceHistory history = taskHistory != null ? taskHistory.get(modelId) : null;
        return history != null ? history.getReliability(windowSize) : 0.0;
    }

    /**
     * Checks if model performance is improving or degrading.
     */
    public PerformanceTrend getPerformanceTrend(TaskType taskType, String modelId) {
        Map<String, ModelPerformanceHistory> taskHistory = historyByTaskType.get(taskType);
        ModelPerformanceHistory history = taskHistory != null ? taskHistory.get(modelId) : null;
        if (history == null) return PerformanceTrend.STABLE;

        double firstHalf = history.getAverageSuccessRateFirstHalf(windowSize);
        double secondHalf = history.getRecentSuccessRate(windowSize / 2);

        if (secondHalf > firstHalf + 0.1) return PerformanceTrend.IMPROVING;
        if (secondHalf < firstHalf - 0.1) return PerformanceTrend.DEGRADING;
        return PerformanceTrend.STABLE;
    }

    private ModelPerformanceHistory getHistory(TaskType taskType, String modelId) {
        return historyByTaskType
            .computeIfAbsent(taskType, k -> new HashMap<>())
            .computeIfAbsent(modelId, k -> new ModelPerformanceHistory());
    }

    public enum PerformanceTrend {
        IMPROVING, STABLE, DEGRADING
    }

    /**
     * Stores execution history for a model on a task type.
     */
    private static class ModelPerformanceHistory {
        private final List<Boolean> successes = new ArrayList<>();
        private final List<Long> latencies = new ArrayList<>();

        public synchronized void record(boolean success, long latencyMs) {
            successes.add(success);
            latencies.add(latencyMs);
        }

        public synchronized double getRecentSuccessRate(int window) {
            int size = Math.min(window, successes.size());
            if (size == 0) return 0.0;

            int successCount = 0;
            for (int i = successes.size() - size; i < successes.size(); i++) {
                if (successes.get(i)) successCount++;
            }
            return (double) successCount / size;
        }

        public synchronized double getRecentAverageLatency(int window) {
            int size = Math.min(window, latencies.size());
            if (size == 0) return 0.0;

            long sum = 0;
            for (int i = latencies.size() - size; i < latencies.size(); i++) {
                sum += latencies.get(i);
            }
            return (double) sum / size;
        }

        public synchronized double getLifetimeSuccessRate() {
            if (successes.isEmpty()) return 0.0;
            long successCount = successes.stream().filter(s -> s).count();
            return (double) successCount / successes.size();
        }

        public synchronized double getAverageSuccessRateFirstHalf(int window) {
            int size = Math.min(window / 2, successes.size() / 2);
            if (size == 0) return 0.0;

            int successCount = 0;
            for (int i = 0; i < size; i++) {
                if (successes.get(i)) successCount++;
            }
            return (double) successCount / size;
        }

        public synchronized double getReliability(int window) {
            int size = Math.min(window, successes.size());
            if (size < 5) return (double) size / 5; // Low reliability with few samples

            // Reliability based on consistency (inverse variance)
            int successCount = 0;
            for (int i = successes.size() - size; i < successes.size(); i++) {
                if (successes.get(i)) successCount++;
            }
            double rate = (double) successCount / size;
            return rate * 0.5 + 0.5; // Normalize to 0.5-1.0 range
        }
    }
}