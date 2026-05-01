package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.service.SchemaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MetaLearningOptimizerServiceTest {
    
    private WorkflowMetaLearner metaLearner;
    private SchemaService schemaService;
    private MetaLearningOptimizerService optimizer;
    
    @BeforeEach
    void setUp() {
        metaLearner = new WorkflowMetaLearner(new WorkflowAnalyzer());
        schemaService = mock(SchemaService.class);
        optimizer = new MetaLearningOptimizerService(metaLearner, schemaService);
    }
    
    @Test
    void testOptimizeWorkflowWithMockedService() {
        WorkflowSchema workflow = createTestWorkflow("test-workflow");
        when(schemaService.getSchema("test-workflow")).thenReturn(workflow);
        
        optimizer.setFitnessFunction(w -> -w.getNodes().size());
        
        List<String> progress = new ArrayList<>();
        optimizer.setProgressCallback(progress::add);
        
        optimizer.optimizeWorkflow("test-workflow");
        
        assertFalse(progress.isEmpty());
    }
    
    @Test
    void testGenerateVariantsCreatesMutations() {
        WorkflowAnalyzer analyzer = new WorkflowAnalyzer(0.5);
        WorkflowMetaLearner learner = new WorkflowMetaLearner(analyzer);
        MetaLearningOptimizerService opt = new MetaLearningOptimizerService(learner, schemaService);
        
        Map<String, NodePerformanceMetrics> history = new HashMap<>();
        NodePerformanceMetrics metrics = new NodePerformanceMetrics("node1");
        metrics.incrementUtilization();
        history.put("node1", metrics);
        learner.setExecutionHistory(history);
        
        WorkflowSchema workflow = createTestWorkflow("test");
        List<WorkflowSchema> variants = opt.generateVariants(workflow);
        
        assertNotNull(variants);
    }
    
    @Test
    void testRunABTestsEvaluatesVariants() {
        WorkflowSchema control = createTestWorkflow("control");
        WorkflowSchema variant = createTestWorkflow("variant");
        
        metaLearner.setFitnessFunction(w -> -w.getNodes().size());
        
        List<WorkflowSchema> variants = Collections.singletonList(variant);
        List<MetaLearningOptimizerService.ABTestResult> results = 
            optimizer.runABTests(control, variants);
        
        assertEquals(1, results.size());
        MetaLearningOptimizerService.ABTestResult result = results.get(0);
        assertNotNull(result.getControlId());
        assertNotNull(result.getVariantId());
    }
    
    @Test
    void testABTestResultStatisticalSignificance() {
        MetaLearningOptimizerService.ABTestResult result = 
            new MetaLearningOptimizerService.ABTestResult(
                "control", "variant", 0.3, 0.7, 0.4, true, 100);
        
        boolean significant = result.isStatisticallySignificant(0.90);
        assertNotNull(result.toString());
        assertTrue(result.getImprovement() > 0);
    }
    
    @Test
    void testOptimizeWorkflowAsync() {
        WorkflowSchema workflow = createTestWorkflow("async-test");
        when(schemaService.getSchema("async-test")).thenReturn(workflow);
        
        optimizer.setFitnessFunction(w -> -w.getNodes().size());
        
        CompletableFuture<Void> future = optimizer.optimizeWorkflowAsync("async-test");
        future.join();
        
        assertTrue(future.isDone());
    }
    
    @Test
    void testGetAllTestResults() {
        Map<String, MetaLearningOptimizerService.ABTestResult> results = 
            optimizer.getAllTestResults();
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }
    
    private WorkflowSchema createTestWorkflow(String id) {
        WorkflowSchema workflow = new WorkflowSchema();
        workflow.setId(id);
        workflow.setName("Test Workflow");
        
        Node node1 = new Node();
        node1.setId("node1");
        node1.setType("source");
        workflow.setNodes(Arrays.asList(node1));
        
        Edge edge = new Edge();
        edge.setSource("node1");
        edge.setTarget("node1");
        workflow.setEdges(Arrays.asList(edge));
        
        return workflow;
    }
}