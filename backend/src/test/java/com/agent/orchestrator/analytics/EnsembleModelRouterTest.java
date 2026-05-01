package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

/**
 * Unit tests for EnsembleModelRouter.
 */
class EnsembleModelRouterTest {

    @Test
    void shouldRouteBasedOnTaskType() {
        TaskTypeClassifier classifier = new TaskTypeClassifier();
        ModelPreferenceTracker tracker = new ModelPreferenceTracker();
        EnsembleModelRouter router = new EnsembleModelRouter(classifier, tracker);
        
        // Record preferences
        tracker.recordResult(TaskType.REASONING, "claude-3", true, 5000, 0.5);
        tracker.recordResult(TaskType.REASONING, "gpt-4", false, 3000, 1.0);
        tracker.setExplorationRate(0.0);
        
        // Create workflow with 3 agents = REASONING
        WorkflowSchema workflow = createWorkflowWithNodes(3, 1, 1);
        
        String model = router.getModelForWorkflow(workflow, Arrays.asList("claude-3", "gpt-4"));
        assertEquals("claude-3", model);
    }

    @Test
    void shouldUseManualOverride() {
        TaskTypeClassifier classifier = new TaskTypeClassifier();
        ModelPreferenceTracker tracker = new ModelPreferenceTracker();
        EnsembleModelRouter router = new EnsembleModelRouter(classifier, tracker);
        
        router.setManualOverride("claude-3");
        
        WorkflowSchema workflow = createWorkflowWithNodes(1, 1, 1);
        String model = router.getModelForWorkflow(workflow, Arrays.asList("claude-3", "gpt-4"));
        assertEquals("claude-3", model);
    }

    @Test
    void shouldClearManualOverride() {
        TaskTypeClassifier classifier = new TaskTypeClassifier();
        ModelPreferenceTracker tracker = new ModelPreferenceTracker();
        EnsembleModelRouter router = new EnsembleModelRouter(classifier, tracker);
        
        router.setManualOverride("claude-3");
        router.clearManualOverride();
        
        WorkflowSchema workflow = createWorkflowWithNodes(1, 1, 1);
        String model = router.getModelForWorkflow(workflow, Arrays.asList("gpt-4"));
        assertEquals("gpt-4", model);
    }

    @Test
    void shouldReturnTaskType() {
        TaskTypeClassifier classifier = new TaskTypeClassifier();
        ModelPreferenceTracker tracker = new ModelPreferenceTracker();
        EnsembleModelRouter router = new EnsembleModelRouter(classifier, tracker);
        
        WorkflowSchema workflow = createWorkflowWithNodes(3, 1, 1);
        TaskType taskType = router.getTaskType(workflow);
        
        assertEquals(TaskType.REASONING, taskType);
    }

    @Test
    void shouldProvideRoutingExplanation() {
        TaskTypeClassifier classifier = new TaskTypeClassifier();
        ModelPreferenceTracker tracker = new ModelPreferenceTracker();
        EnsembleModelRouter router = new EnsembleModelRouter(classifier, tracker);
        
        WorkflowSchema workflow = createWorkflowWithNodes(3, 1, 1);
        String explanation = router.getRoutingExplanation(workflow, "claude-3");
        
        assertTrue(explanation.contains("claude-3"));
        assertTrue(explanation.contains("REASONING"));
    }

    private WorkflowSchema createWorkflowWithNodes(int agentCount, int sourceCount, int outputCount) {
        WorkflowSchema workflow = new WorkflowSchema();
        List<Node> nodes = new ArrayList<>();
        
        for (int i = 0; i < sourceCount; i++) {
            Node node = new Node();
            node.setId("source-" + i);
            node.setType("source");
            nodes.add(node);
        }
        for (int i = 0; i < agentCount; i++) {
            Node node = new Node();
            node.setId("agent-" + i);
            node.setType("agent");
            nodes.add(node);
        }
        for (int i = 0; i < outputCount; i++) {
            Node node = new Node();
            node.setId("output-" + i);
            node.setType("output");
            nodes.add(node);
        }
        
        workflow.setNodes(nodes);
        return workflow;
    }
}