package com.agent.orchestrator.analytics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

/**
 * Unit tests for ExplanationGenerator.
 */
class ExplanationGeneratorTest {

    @Test
    void shouldGenerateExplanationForModelSelection() {
        ExplanationGenerator generator = new ExplanationGenerator();
        
        List<ExplanationGenerator.Alternative> alternatives = Arrays.asList(
            new ExplanationGenerator.Alternative("claude-3", "Claude 3", 0.95, "High success rate"),
            new ExplanationGenerator.Alternative("gpt-4", "GPT-4", 0.85, "Good reasoning")
        );
        
        ExplanationGenerator.DecisionContext context = new ExplanationGenerator.DecisionContext(
            ExplanationGenerator.DecisionType.MODEL_SELECTION,
            "claude-3", "Claude 3",
            alternatives,
            new HashMap<>()
        );
        
        String explanation = generator.generateExplanation(context);
        
        assertNotNull(explanation);
        assertTrue(explanation.contains("Claude 3"));
    }
    
    @Test
    void shouldGenerateExplanationForNodeSelection() {
        ExplanationGenerator generator = new ExplanationGenerator();
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("taskType", "reasoning");
        metadata.put("nodeType", "agent");
        
        ExplanationGenerator.DecisionContext context = new ExplanationGenerator.DecisionContext(
            ExplanationGenerator.DecisionType.NODE_SELECTION,
            "agent-1", "ReasoningAgent",
            Collections.emptyList(),
            metadata
        );
        
        String explanation = generator.generateExplanation(context);
        
        assertNotNull(explanation);
        assertTrue(explanation.contains("ReasoningAgent"));
    }
    
    @Test
    void shouldGenerateExplanationForRoutingDecision() {
        ExplanationGenerator generator = new ExplanationGenerator();
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("confidence", 0.85);
        metadata.put("condition", "success");
        
        ExplanationGenerator.DecisionContext context = new ExplanationGenerator.DecisionContext(
            ExplanationGenerator.DecisionType.ROUTING_DECISION,
            "next-node", "Next Node",
            Collections.emptyList(),
            metadata
        );
        
        String explanation = generator.generateExplanation(context);
        
        assertNotNull(explanation);
        assertTrue(explanation.contains("Next Node"));
    }
    
    @Test
    void shouldGenerateExplanationForNodeSkip() {
        ExplanationGenerator generator = new ExplanationGenerator();
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("skipReason", "condition not met");
        
        ExplanationGenerator.DecisionContext context = new ExplanationGenerator.DecisionContext(
            ExplanationGenerator.DecisionType.NODE_SKIP,
            "optional-node", "Optional Node",
            Collections.emptyList(),
            metadata
        );
        
        String explanation = generator.generateExplanation(context);
        
        assertNotNull(explanation);
        assertTrue(explanation.contains("skipped"));
    }
    
    @Test
    void shouldGenerateExplanationForWorkflowStep() {
        ExplanationGenerator generator = new ExplanationGenerator();
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("previousResult", "completed");
        metadata.put("nextStep", "output-node");
        
        ExplanationGenerator.DecisionContext context = new ExplanationGenerator.DecisionContext(
            ExplanationGenerator.DecisionType.WORKFLOW_STEP,
            "agent-node", "Agent Node",
            Collections.emptyList(),
            metadata
        );
        
        String explanation = generator.generateExplanation(context);
        
        assertNotNull(explanation);
        assertTrue(explanation.contains("Agent Node"));
    }
    
    @Test
    void shouldHandleNullContext() {
        ExplanationGenerator generator = new ExplanationGenerator();
        
        String explanation = generator.generateExplanation(null);
        
        assertEquals("No decision context provided.", explanation);
    }
    
    @Test
    void shouldHandleEmptyAlternatives() {
        ExplanationGenerator generator = new ExplanationGenerator();
        
        ExplanationGenerator.DecisionContext context = new ExplanationGenerator.DecisionContext(
            ExplanationGenerator.DecisionType.MODEL_SELECTION,
            "claude-3", "Claude 3",
            Collections.emptyList(),
            new HashMap<>()
        );
        
        String explanation = generator.generateExplanation(context);
        
        assertNotNull(explanation);
        assertTrue(explanation.contains("Claude 3"));
    }
    
    @Test
    void shouldSetTraceAnalyzer() {
        ExplanationGenerator generator = new ExplanationGenerator();
        ReasoningTraceAnalyzer traceAnalyzer = new ReasoningTraceAnalyzer();
        
        generator.setTraceAnalyzer(traceAnalyzer);
        
        assertTrue(generator.isUsingTraceAnalyzer());
    }
}