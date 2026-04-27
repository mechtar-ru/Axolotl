package com.agent.orchestrator.analytics;

import java.util.*;

/**
 * Reasoner for time-dependent workflows and forecasting.
 * 
 * Integration points:
 * - processTemporalData(timestamps, values) - processes time series data
 * - applyTemporalTransform(series, window) - applies rolling window transformation
 * - forecastMovingAverage(series, window) - predicts using moving average
 * - forecastLinearTrend(series) - predicts based on linear trend
 * - detectTemporalPatterns(series) - detects seasonality and trends
 * 
 * Usage in workflows: Use with temporal nodes that need time series analysis
 * and forecasting capabilities.
 */


public class TemporalReasoner {
    
    public record TemporalDataPoint(long timestamp, double value) {}
    
    public enum TemporalPattern {
        UPTREND, DOWNTREND, STABLE, SEASONAL, CYCLIC, UNKNOWN
    }
    
    /**
     * Processes temporal data and extracts features.
     */
    public List<TemporalDataPoint> processTemporalData(List<Long> timestamps, List<Double> values) {
        if (timestamps == null || values == null || timestamps.size() != values.size()) {
            return Collections.emptyList();
        }
        
        List<TemporalDataPoint> dataPoints = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            dataPoints.add(new TemporalDataPoint(timestamps.get(i), values.get(i)));
        }
        
        return dataPoints;
    }
    
    /**
     * Applies rolling window transformation (e.g., rolling mean).
     */
    public List<Double> applyTemporalTransform(List<Double> series, int window) {
        if (series == null || series.isEmpty() || window <= 0) {
            return Collections.emptyList();
        }
        
        List<Double> transformed = new ArrayList<>();
        
        for (int i = 0; i < series.size(); i++) {
            int start = Math.max(0, i - window + 1);
            int end = i + 1;
            double sum = 0.0;
            int count = 0;
            
            for (int j = start; j < end; j++) {
                sum += series.get(j);
                count++;
            }
            
            transformed.add(sum / count);
        }
        
        return transformed;
    }
    
    /**
     * Generates forecast using simple moving average.
     */
    public List<Double> forecastMovingAverage(List<Double> series, int window, int horizon) {
        if (series == null || series.isEmpty() || window <= 0 || horizon <= 0) {
            return Collections.emptyList();
        }
        
        // Calculate the moving average of the last window values
        double sum = 0.0;
        int startIndex = Math.max(0, series.size() - window);
        for (int i = startIndex; i < series.size(); i++) {
            sum += series.get(i);
        }
        double ma = sum / Math.min(window, series.size());
        
        // Return forecast for horizon steps
        List<Double> forecast = new ArrayList<>();
        for (int i = 0; i < horizon; i++) {
            forecast.add(ma);
        }
        
        return forecast;
    }
    
    /**
     * Generates forecast using linear trend extrapolation.
     */
    public List<Double> forecastLinearTrend(List<Double> series, int horizon) {
        if (series == null || series.size() < 2 || horizon <= 0) {
            return Collections.emptyList();
        }
        
        // Calculate linear regression (y = mx + b)
        int n = series.size();
        double xSum = 0, ySum = 0, xySum = 0, xxSum = 0;
        
        for (int i = 0; i < n; i++) {
            xSum += i;
            ySum += series.get(i);
            xySum += i * series.get(i);
            xxSum += i * i;
        }
        
        double slope = (n * xySum - xSum * ySum) / (n * xxSum - xSum * xSum);
        double intercept = (ySum - slope * xSum) / n;
        
        // Generate forecast
        List<Double> forecast = new ArrayList<>();
        for (int i = 0; i < horizon; i++) {
            double predicted = slope * (n + i) + intercept;
            forecast.add(predicted);
        }
        
        return forecast;
    }
    
    /**
     * Detects temporal patterns in the series.
     */
    public TemporalPattern detectTemporalPatterns(List<Double> series) {
        if (series == null || series.size() < 3) {
            return TemporalPattern.UNKNOWN;
        }
        
        // Check for trend
        double firstHalfAvg = series.subList(0, series.size() / 2).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double secondHalfAvg = series.subList(series.size() / 2, series.size()).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        
        double diff = secondHalfAvg - firstHalfAvg;
        
        if (Math.abs(diff) < 0.1) {
            return TemporalPattern.STABLE;
        } else if (diff > 0) {
            return TemporalPattern.UPTREND;
        } else {
            return TemporalPattern.DOWNTREND;
        }
    }
    
    /**
     * Calculates differencing for the series.
     */
    public List<Double> calculateDifference(List<Double> series, int order) {
        if (series == null || series.size() <= order || order <= 0) {
            return Collections.emptyList();
        }
        
        List<Double> diff = new ArrayList<>();
        for (int i = order; i < series.size(); i++) {
            diff.add(series.get(i) - series.get(i - order));
        }
        
        return diff;
    }
    
    /**
     * Aggregates values by time buckets.
     */
    public Map<Long, Double> aggregateByTimeBucket(List<TemporalDataPoint> dataPoints, long bucketSizeMs) {
        if (dataPoints == null || dataPoints.isEmpty() || bucketSizeMs <= 0) {
            return Collections.emptyMap();
        }
        
        Map<Long, List<Double>> buckets = new HashMap<>();
        
        for (TemporalDataPoint point : dataPoints) {
            long bucketKey = (point.timestamp() / bucketSizeMs) * bucketSizeMs;
            buckets.computeIfAbsent(bucketKey, k -> new ArrayList<>()).add(point.value());
        }
        
        Map<Long, Double> aggregated = new HashMap<>();
        for (Map.Entry<Long, List<Double>> entry : buckets.entrySet()) {
            double avg = entry.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            aggregated.put(entry.getKey(), avg);
        }
        
        return aggregated;
    }
}