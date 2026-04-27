package com.agent.orchestrator.analytics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

/**
 * Unit tests for ReasoningTraceAnalyzer.
 */
class ReasoningTraceAnalyzerTest {

    @Test
    void shouldTraceDecision() {
        ReasoningTraceAnalyzer tracer = new ReasoningTraceAnalyzer();
        tracer.setEnabled(true);
        
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("taskType", "reasoning");
        
        Map<String, Double> scores = new HashMap<>();
        scores.put("claude-3", 0.95);
        scores.put("gpt-4", 0.85);
        
        tracer.traceDecision("exec-1", "MODEL_SELECTION", inputs, scores, "claude-3");
        
        List<ReasoningTraceAnalyzer.DecisionTrace> traces = tracer.getTracesForExecution("exec-1");
        
        assertEquals(1, traces.size());
    }
    
    @Test
    void shouldGetTracesByType() {
        ReasoningTraceAnalyzer tracer = new ReasoningTraceAnalyzer();
        tracer.setEnabled(true);
        
        Map<String, Object> inputs = new HashMap<>();
        Map<String, Double> scores = new HashMap<>();
        
        tracer.traceDecision("exec-1", "MODEL_SELECTION", inputs, scores, "claude-3");
        tracer.traceDecision("exec-1", "NODE_SELECTION", inputs, scores, "agent-1");
        
        List<ReasoningTraceAnalyzer.DecisionTrace> modelTraces = tracer.getTracesByType("exec-1", "MODEL_SELECTION");
        
        assertEquals(1, modelTraces.size());
    }
    
    @Test
    void shouldClearTraces() {
        ReasoningTraceAnalyzer tracer = new ReasoningTraceAnalyzer();
        tracer.setEnabled(true);
        
        Map<String, Object> inputs = new HashMap<>();
        Map<String, Double> scores = new HashMap<>();
        
        tracer.traceDecision("exec-1", "MODEL_SELECTION", inputs, scores, "claude-3");
        tracer.clearTraces("exec-1");
        
        List<ReasoningTraceAnalyzer.DecisionTrace> traces = tracer.getTracesForExecution("exec-1");
        
        assertTrue(traces.isEmpty());
    }
    
    @Test
    void shouldGetTraceSummary() {
        ReasoningTraceAnalyzer tracer = new ReasoningTraceAnalyzer();
        tracer.setEnabled(true);
        
        Map<String, Object> inputs = new HashMap<>();
        Map<String, Double> scores = new HashMap<>();
        
        tracer.traceDecision("exec-1", "MODEL_SELECTION", inputs, scores, "claude-3");
        tracer.traceDecision("exec-1", "NODE_SELECTION", inputs, scores, "agent-1");
        
        Map<String, Object> summary = tracer.getTraceSummary("exec-1");
        
        assertEquals(2, summary.get("totalTraces"));
    }
    
    @Test
    void shouldRespectSamplingRate() {
        ReasoningTraceAnalyzer tracer = new ReasoningTraceAnalyzer();
        tracer.setEnabled(true);
        tracer.setSamplingRate(0.0);
        
        Map<String, Object> inputs = new HashMap<>();
        Map<String, Double> scores = new HashMap<>();
        
        tracer.traceDecision("exec-1", "MODEL_SELECTION", inputs, scores, "claude-3");
        
        List<ReasoningTraceAnalyzer.DecisionTrace> traces = tracer.getTracesForExecution("exec-1");
        
        assertTrue(traces.isEmpty());
    }
    
    @Test
    void shouldNotTraceWhenDisabled() {
        ReasoningTraceAnalyzer tracer = new ReasoningTraceAnalyzer();
        tracer.setEnabled(false);
        
        Map<String, Object> inputs = new HashMap<>();
        Map<String, Double> scores = new HashMap<>();
        
        tracer.traceDecision("exec-1", "MODEL_SELECTION", inputs, scores, "claude-3");
        
        List<ReasoningTraceAnalyzer.DecisionTrace> traces = tracer.getTracesForExecution("exec-1");
        
        assertTrue(traces.isEmpty());
    }
    
    @Test
    void shouldStartAndEndExecution() {
        ReasoningTraceAnalyzer tracer = new ReasoningTraceAnalyzer();
        
        tracer.startExecution("exec-1");
        tracer.endExecution("exec-1");
        
        assertDoesNotThrow(() -> tracer.getTracesForExecution("exec-1"));
    }
    
    @Test
    void shouldReturnTotalTraceCount() {
        ReasoningTraceAnalyzer tracer = new ReasoningTraceAnalyzer();
        tracer.setEnabled(true);
        
        Map<String, Object> inputs = new HashMap<>();
        Map<String, Double> scores = new HashMap<>();
        
        tracer.traceDecision("exec-1", "MODEL_SELECTION", inputs, scores, "claude-3");
        tracer.traceDecision("exec-2", "MODEL_SELECTION", inputs, scores, "gpt-4");
        
        assertEquals(2, tracer.getTotalTraceCount());
    }
}