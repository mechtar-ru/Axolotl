package com.agent.orchestrator.analytics;

import org.junit.jupiter.api.Test;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.Edge;
import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

public class AdaptiveWorkflowEngineTest {
    
    @Test
    void testConstructor() {
        AdaptiveWorkflowEngine engine = new AdaptiveWorkflowEngine();
        
        assertNotNull(engine.getUsageTracking());
        assertNotNull(engine.getWorkflowAnalyzer());
        assertNotNull(engine.getWorkflowPruner());
    }
    
    @Test
    void testTriggerAdaptationNullWorkflow() {
        AdaptiveWorkflowEngine engine = new AdaptiveWorkflowEngine();
        
        var results = engine.triggerAdaptation(null);
        
        assertTrue(results.isEmpty());
    }
    
    @Test
    void testTriggerAdaptationEmptyWorkflow() {
        AdaptiveWorkflowEngine engine = new AdaptiveWorkflowEngine();
        
        WorkflowSchema workflow = new WorkflowSchema();
        workflow.setNodes(new ArrayList<>());
        
        var results = engine.triggerAdaptation(workflow);
        
        assertEquals(2, results.size()); // "Collected metrics" + "No adaptations needed"
        assertTrue(results.get(0).contains("0 nodes"));
    }
    
    @Test
    void testTriggerAdaptationSingleNode() {
        AdaptiveWorkflowEngine engine = new AdaptiveWorkflowEngine();
        
        Node node = new Node();
        node.setId("node1");
        node.setType("source");
        
        WorkflowSchema workflow = new WorkflowSchema();
        workflow.setId("workflow1");
        workflow.setName("Test Workflow");
        workflow.setNodes(Arrays.asList(node));
        workflow.setEdges(new ArrayList<>());
        
        var results = engine.triggerAdaptation(workflow);
        assertFalse(results.isEmpty());
    }
    
    @Test
    void testCoordinateWorkflowAnalysis() {
        AdaptiveWorkflowEngine engine = new AdaptiveWorkflowEngine();
        
        Node node = new Node();
        node.setId("node1");
        
        WorkflowSchema workflow = new WorkflowSchema();
        workflow.setNodes(Arrays.asList(node));
        
        var analysis = engine.coordinateWorkflowAnalysis(workflow, 
            new java.util.HashMap<>());
        
        assertNotNull(analysis);
    }
    
    @Test
    void testApplyAdaptationNullWorkflow() {
        AdaptiveWorkflowEngine engine = new AdaptiveWorkflowEngine();
        
        var result = engine.applyAdaptation(null, Arrays.asList("node1"));
        
        assertNull(result);
    }
    
    @Test
    void testApplyAdaptationNullNodes() {
        AdaptiveWorkflowEngine engine = new AdaptiveWorkflowEngine();
        
        WorkflowSchema workflow = new WorkflowSchema();
        workflow.setNodes(new ArrayList<>());
        
        var result = engine.applyAdaptation(workflow, null);
        
        assertNotNull(result);
    }
    
    @Test
    void testSchedulePeriodicAdaptation() {
        AdaptiveWorkflowEngine engine = new AdaptiveWorkflowEngine();
        
        // Just verify it doesn't throw
        engine.schedulePeriodicAdaptation(60000);
        engine.cancelScheduledAdaptation();
    }
    
    @Test
    void testGetAdaptationHistoryEmpty() {
        AdaptiveWorkflowEngine engine = new AdaptiveWorkflowEngine();
        
        var history = engine.getAdaptationHistory();
        
        assertTrue(history.isEmpty());
    }
    
    @Test
    void testGetAdaptationHistoryAfterTrigger() {
        AdaptiveWorkflowEngine engine = new AdaptiveWorkflowEngine();
        
        Node node = new Node();
        node.setId("node1");
        node.setType("source");
        
        WorkflowSchema workflow = new WorkflowSchema();
        workflow.setId("workflow1");
        workflow.setNodes(Arrays.asList(node));
        workflow.setEdges(new ArrayList<>());
        
        var results = engine.triggerAdaptation(workflow);
        
        // Verify trigger returns analysis results
        assertFalse(results.isEmpty());
        
        var history = engine.getAdaptationHistory();
        assertNotNull(history);
        // History may or may not be empty depending on implementation
    }
}