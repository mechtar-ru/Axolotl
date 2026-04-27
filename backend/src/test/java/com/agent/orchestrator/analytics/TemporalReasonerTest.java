package com.agent.orchestrator.analytics;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

public class TemporalReasonerTest {
    
    @Test
    void testProcessTemporalData() {
        TemporalReasoner reasoner = new TemporalReasoner();
        
        List<Long> timestamps = Arrays.asList(1000L, 2000L, 3000L);
        List<Double> values = Arrays.asList(10.0, 20.0, 30.0);
        
        var dataPoints = reasoner.processTemporalData(timestamps, values);
        
        assertEquals(3, dataPoints.size());
        assertEquals(10.0, dataPoints.get(0).value());
    }
    
    @Test
    void testProcessTemporalDataMismatch() {
        TemporalReasoner reasoner = new TemporalReasoner();
        
        List<Long> timestamps = Arrays.asList(1000L, 2000L);
        List<Double> values = Arrays.asList(10.0, 20.0, 30.0);
        
        var dataPoints = reasoner.processTemporalData(timestamps, values);
        
        assertEquals(0, dataPoints.size());
    }
    
    @Test
    void testApplyRollingMean() {
        TemporalReasoner reasoner = new TemporalReasoner();
        
        List<Double> series = Arrays.asList(1.0, 2.0, 3.0, 4.0, 5.0);
        
        var transformed = reasoner.applyTemporalTransform(series, 3);
        
        assertEquals(5, transformed.size());
        assertEquals(2.0, transformed.get(2)); // (1+2+3)/3 = 2
    }
    
    @Test
    void testForecastMovingAverage() {
        TemporalReasoner reasoner = new TemporalReasoner();
        
        List<Double> series = Arrays.asList(1.0, 2.0, 3.0, 4.0, 5.0);
        
        var forecast = reasoner.forecastMovingAverage(series, 3, 2);
        
        assertEquals(2, forecast.size());
        assertEquals(4.0, forecast.get(0), 0.1); // (3+4+5)/3 = 4
    }
    
    @Test
    void testForecastLinearTrend() {
        TemporalReasoner reasoner = new TemporalReasoner();
        
        // Linear trend: y = 2x
        List<Double> series = Arrays.asList(0.0, 2.0, 4.0, 6.0, 8.0);
        
        var forecast = reasoner.forecastLinearTrend(series, 2);
        
        assertEquals(2, forecast.size());
        assertEquals(10.0, forecast.get(0), 0.5); // continuing trend
    }
    
    @Test
    void testDetectUptrend() {
        TemporalReasoner reasoner = new TemporalReasoner();
        
        List<Double> series = Arrays.asList(1.0, 2.0, 3.0, 4.0, 5.0);
        
        var pattern = reasoner.detectTemporalPatterns(series);
        
        assertEquals(TemporalReasoner.TemporalPattern.UPTREND, pattern);
    }
    
    @Test
    void testDetectDowntrend() {
        TemporalReasoner reasoner = new TemporalReasoner();
        
        List<Double> series = Arrays.asList(5.0, 4.0, 3.0, 2.0, 1.0);
        
        var pattern = reasoner.detectTemporalPatterns(series);
        
        assertEquals(TemporalReasoner.TemporalPattern.DOWNTREND, pattern);
    }
    
    @Test
    void testDetectStable() {
        TemporalReasoner reasoner = new TemporalReasoner();
        
        List<Double> series = Arrays.asList(1.0, 1.1, 0.9, 1.0, 1.0);
        
        var pattern = reasoner.detectTemporalPatterns(series);
        
        assertEquals(TemporalReasoner.TemporalPattern.STABLE, pattern);
    }
    
    @Test
    void testCalculateDifference() {
        TemporalReasoner reasoner = new TemporalReasoner();
        
        List<Double> series = Arrays.asList(1.0, 2.0, 3.0, 4.0, 5.0);
        
        var diff = reasoner.calculateDifference(series, 1);
        
        assertEquals(4, diff.size());
        assertEquals(1.0, diff.get(0)); // 2-1
    }
    
    @Test
    void testAggregateByTimeBucket() {
        TemporalReasoner reasoner = new TemporalReasoner();
        
        List<TemporalReasoner.TemporalDataPoint> dataPoints = Arrays.asList(
            new TemporalReasoner.TemporalDataPoint(1000L, 10.0),
            new TemporalReasoner.TemporalDataPoint(1500L, 20.0),
            new TemporalReasoner.TemporalDataPoint(3000L, 30.0)
        );
        
        var aggregated = reasoner.aggregateByTimeBucket(dataPoints, 2000L);
        
        assertEquals(2, aggregated.size());
    }
    
    @Test
    void testEmptySeries() {
        TemporalReasoner reasoner = new TemporalReasoner();
        
        var forecast = reasoner.forecastMovingAverage(new ArrayList<>(), 3, 2);
        
        assertEquals(0, forecast.size());
    }
}