package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.WorkflowSchema;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

public class WorkflowMetaLearnerTest {
    @Test
    void testGenerateMutationsRemovesUnderutilizedNodes() {
        WorkflowAnalyzer analyzer = new WorkflowAnalyzer(0.5);
        WorkflowMetaLearner learner = new WorkflowMetaLearner(analyzer);

        Map<String, NodePerformanceMetrics> history = new HashMap<>();
        NodePerformanceMetrics metrics = new NodePerformanceMetrics("node1");
        metrics.incrementUtilization();
        history.put("node1", metrics);
        learner.setExecutionHistory(history);

        WorkflowSchema workflow = new WorkflowSchema();
        workflow.setId("test");
        Node node1 = new Node();
        node1.setId("node1");
        workflow.setNodes(Arrays.asList(node1));
        workflow.setEdges(new ArrayList<>());

        List<WorkflowSchema> mutations = learner.generateMutations(workflow);
        assertEquals(1, mutations.size());
        assertTrue(mutations.get(0).getNodes().isEmpty());
    }

    @Test
    void testOptimizeImprovesFitness() {
        WorkflowAnalyzer analyzer = new WorkflowAnalyzer(0.5);
        WorkflowMetaLearner learner = new WorkflowMetaLearner(analyzer);

        learner.setFitnessFunction(workflow -> -workflow.getNodes().size());

        Map<String, NodePerformanceMetrics> history = new HashMap<>();
        NodePerformanceMetrics metrics = new NodePerformanceMetrics("node1");
        metrics.incrementUtilization();
        history.put("node1", metrics);
        learner.setExecutionHistory(history);

        WorkflowSchema initial = new WorkflowSchema();
        initial.setId("initial");
        Node node1 = new Node();
        node1.setId("node1");
        initial.setNodes(Arrays.asList(node1));
        initial.setEdges(new ArrayList<>());

        WorkflowSchema optimized = learner.optimize(initial, 1);
        assertEquals(0, optimized.getNodes().size());
    }

    @Test
    void testCrossoverCombinesWorkflows() {
        WorkflowMetaLearner learner = new WorkflowMetaLearner(new WorkflowAnalyzer());

        WorkflowSchema parent1 = new WorkflowSchema();
        parent1.setId("p1");
        Node node1 = new Node();
        node1.setId("n1");
        parent1.setNodes(Arrays.asList(node1));
        parent1.setEdges(new ArrayList<>());

        WorkflowSchema parent2 = new WorkflowSchema();
        parent2.setId("p2");
        Node node2 = new Node();
        node2.setId("n2");
        parent2.setNodes(Arrays.asList(node2));
        parent2.setEdges(new ArrayList<>());

        WorkflowSchema child = learner.crossover(parent1, parent2);
        assertEquals(2, child.getNodes().size());
    }
}
