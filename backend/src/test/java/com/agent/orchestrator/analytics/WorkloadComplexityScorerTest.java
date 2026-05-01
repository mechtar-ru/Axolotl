package com.agent.orchestrator.analytics;

import org.junit.jupiter.api.Test;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.Edge;
import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

public class WorkloadComplexityScorerTest {
    
    @Test
    void testComputeComplexityEmptyWorkflow() {
        WorkloadComplexityScorer scorer = new WorkloadComplexityScorer();
        
        WorkflowSchema workflow = new WorkflowSchema();
        workflow.setNodes(new ArrayList<>());
        
        double score = scorer.computeComplexity(workflow);
        
        assertEquals(0.0, score, 0.001);
    }
    
    @Test
    void testComputeComplexitySimpleWorkflow() {
        WorkloadComplexityScorer scorer = new WorkloadComplexityScorer();
        
        Node node1 = new Node();
        node1.setId("node1");
        Node node2 = new Node();
        node2.setId("node2");
        
        Edge edge = new Edge();
        edge.setSource("node1");
        edge.setTarget("node2");
        
        WorkflowSchema workflow = new WorkflowSchema();
        workflow.setNodes(Arrays.asList(node1, node2));
        workflow.setEdges(Arrays.asList(edge));
        
        double score = scorer.computeComplexity(workflow);
        
        assertTrue(score > 0);
    }
    
    @Test
    void testNormalizeScore() {
        WorkloadComplexityScorer scorer = new WorkloadComplexityScorer();
        
        double normalized = scorer.normalizeScore(50.0);
        
        assertTrue(normalized >= 0.0 && normalized <= 1.0);
    }
    
    @Test
    void testNormalizeScoreZero() {
        WorkloadComplexityScorer scorer = new WorkloadComplexityScorer();
        
        double normalized = scorer.normalizeScore(0.0);
        
        assertEquals(0.0, normalized, 0.001);
    }
    
    @Test
    void testNormalizeScoreExceedsMax() {
        WorkloadComplexityScorer scorer = new WorkloadComplexityScorer();
        
        double normalized = scorer.normalizeScore(200.0);
        
        assertEquals(1.0, normalized, 0.001);
    }
    
    @Test
    void testGetNormalizedComplexity() {
        WorkloadComplexityScorer scorer = new WorkloadComplexityScorer();
        
        Node node = new Node();
        node.setId("node1");
        
        WorkflowSchema workflow = new WorkflowSchema();
        workflow.setNodes(Arrays.asList(node));
        
        double normalized = scorer.getNormalizedComplexity(workflow);
        
        assertTrue(normalized >= 0.0 && normalized <= 1.0);
    }
    
    @Test
    void testGetComplexityFactors() {
        WorkloadComplexityScorer scorer = new WorkloadComplexityScorer();
        
        Node node = new Node();
        node.setId("node1");
        
        WorkflowSchema workflow = new WorkflowSchema();
        workflow.setNodes(Arrays.asList(node));
        
        Map<String, Double> factors = scorer.getComplexityFactors(workflow);
        
        assertEquals(1.0, factors.get("nodeCount"), 0.001);
    }
    
    @Test
    void testGetComplexityFactorsNullWorkflow() {
        WorkloadComplexityScorer scorer = new WorkloadComplexityScorer();
        
        Map<String, Double> factors = scorer.getComplexityFactors(null);
        
        assertEquals(0.0, factors.get("nodeCount"), 0.001);
    }
    
    @Test
    void testCalculateDepthSingleNode() {
        WorkloadComplexityScorer scorer = new WorkloadComplexityScorer();
        
        Node node = new Node();
        node.setId("node1");
        
        WorkflowSchema workflow = new WorkflowSchema();
        workflow.setNodes(Arrays.asList(node));
        
        int depth = scorer.calculateDepth(workflow);
        
        assertEquals(1, depth);
    }
    
    @Test
    void testCalculateDepthNoEdges() {
        WorkloadComplexityScorer scorer = new WorkloadComplexityScorer();
        
        Node node1 = new Node();
        node1.setId("node1");
        Node node2 = new Node();
        node2.setId("node2");
        
        WorkflowSchema workflow = new WorkflowSchema();
        workflow.setNodes(Arrays.asList(node1, node2));
        
        int depth = scorer.calculateDepth(workflow);
        
        assertEquals(1, depth);
    }
    
    @Test
    void testCalculateDepthLinearChain() {
        WorkloadComplexityScorer scorer = new WorkloadComplexityScorer();
        
        Node node1 = new Node();
        node1.setId("node1");
        Node node2 = new Node();
        node2.setId("node2");
        Node node3 = new Node();
        node3.setId("node3");
        
        Edge edge1 = new Edge();
        edge1.setSource("node1");
        edge1.setTarget("node2");
        Edge edge2 = new Edge();
        edge2.setSource("node2");
        edge2.setTarget("node3");
        
        WorkflowSchema workflow = new WorkflowSchema();
        workflow.setNodes(Arrays.asList(node1, node2, node3));
        workflow.setEdges(Arrays.asList(edge1, edge2));
        
        int depth = scorer.calculateDepth(workflow);
        
        assertEquals(3, depth);
    }
    
    @Test
    void testCalculateBranchingFactor() {
        WorkloadComplexityScorer scorer = new WorkloadComplexityScorer();
        
        Node node1 = new Node();
        node1.setId("node1");
        Node node2 = new Node();
        node2.setId("node2");
        Node node3 = new Node();
        node3.setId("node3");
        
        Edge edge1 = new Edge();
        edge1.setSource("node1");
        edge1.setTarget("node2");
        Edge edge2 = new Edge();
        edge2.setSource("node1");
        edge2.setTarget("node3");
        
        WorkflowSchema workflow = new WorkflowSchema();
        workflow.setNodes(Arrays.asList(node1, node2, node3));
        workflow.setEdges(Arrays.asList(edge1, edge2));
        
        double branching = scorer.calculateBranchingFactor(workflow);
        
        assertEquals(2.0 / 3.0, branching, 0.001);
    }
    
    @Test
    void testCustomWeights() {
        WorkloadComplexityScorer scorer = new WorkloadComplexityScorer(0.5, 0.3, 0.2);
        
        assertEquals(0.5, scorer.getNodeWeight(), 0.001);
        assertEquals(0.3, scorer.getDepthWeight(), 0.001);
        assertEquals(0.2, scorer.getEdgeWeight(), 0.001);
    }
    
    @Test
    void testSetWeights() {
        WorkloadComplexityScorer scorer = new WorkloadComplexityScorer();
        
        scorer.setNodeWeight(0.6);
        assertEquals(0.6, scorer.getNodeWeight(), 0.001);
    }
}