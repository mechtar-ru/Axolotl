package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.WorkflowSchema;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TaskTypeClassifier.
 */
class TaskTypeClassifierTest {

    @Test
    void shouldClassifyReasoningForMultipleAgents() {
        TaskTypeClassifier classifier = new TaskTypeClassifier();
        WorkflowSchema workflow = createWorkflowWithNodes(3, 1, 1);

        TaskTypeClassifier.ClassificationResult result = classifier.classify(workflow);

        assertEquals(TaskType.REASONING, result.getTaskType());
        assertEquals(0.85, result.getConfidence(), 0.01);
    }

    @Test
    void shouldClassifyDecisionMakingForConditionNodes() {
        TaskTypeClassifier classifier = new TaskTypeClassifier();
        WorkflowSchema workflow = createWorkflowWithConditionNodes();

        TaskTypeClassifier.ClassificationResult result = classifier.classify(workflow);

        assertEquals(TaskType.DECISION_MAKING, result.getTaskType());
        assertEquals(0.8, result.getConfidence(), 0.01);
    }

    @Test
    void shouldClassifyQuestionAnsweringForStandardWorkflow() {
        TaskTypeClassifier classifier = new TaskTypeClassifier();
        WorkflowSchema workflow = createWorkflowWithNodes(1, 1, 1);

        TaskTypeClassifier.ClassificationResult result = classifier.classify(workflow);

        assertEquals(TaskType.QUESTION_ANSWERING, result.getTaskType());
        assertEquals(0.75, result.getConfidence(), 0.01);
    }

    @Test
    void shouldClassifySummarizationFromPrompt() {
        TaskTypeClassifier classifier = new TaskTypeClassifier();
        WorkflowSchema workflow = createWorkflowWithPrompt("summarize this text");

        TaskTypeClassifier.ClassificationResult result = classifier.classify(workflow);

        assertEquals(TaskType.SUMMARIZATION, result.getTaskType());
        assertEquals(0.9, result.getConfidence(), 0.01);
    }

    @Test
    void shouldClassifyTranslationFromPrompt() {
        TaskTypeClassifier classifier = new TaskTypeClassifier();
        WorkflowSchema workflow = createWorkflowWithPrompt("translate this to French");

        TaskTypeClassifier.ClassificationResult result = classifier.classify(workflow);

        assertEquals(TaskType.TRANSLATION, result.getTaskType());
    }

    @Test
    void shouldClassifyCodeGenerationFromPrompt() {
        TaskTypeClassifier classifier = new TaskTypeClassifier();
        WorkflowSchema workflow = createWorkflowWithPrompt("generate code for sorting");

        TaskTypeClassifier.ClassificationResult result = classifier.classify(workflow);

        assertEquals(TaskType.CODE_GENERATION, result.getTaskType());
    }

    @Test
    void shouldClassifyTextGenerationAsDefault() {
        TaskTypeClassifier classifier = new TaskTypeClassifier();
        WorkflowSchema workflow = createWorkflowWithNodes(1, 1, 0);

        TaskTypeClassifier.ClassificationResult result = classifier.classify(workflow);

        assertEquals(TaskType.TEXT_GENERATION, result.getTaskType());
        assertEquals(0.7, result.getConfidence(), 0.01);
    }

    @Test
    void shouldHandleNullWorkflow() {
        TaskTypeClassifier classifier = new TaskTypeClassifier();
        TaskTypeClassifier.ClassificationResult result = classifier.classify(null);

        assertEquals(TaskType.DATA_PROCESSING, result.getTaskType());
        assertEquals(0.3, result.getConfidence(), 0.01);
    }

    @Test
    void shouldHandleEmptyWorkflow() {
        TaskTypeClassifier classifier = new TaskTypeClassifier();
        WorkflowSchema workflow = new WorkflowSchema();

        TaskTypeClassifier.ClassificationResult result = classifier.classify(workflow);

        assertEquals(TaskType.DATA_PROCESSING, result.getTaskType());
    }

    @Test
    void shouldClassifyDataProcessingWithoutAgents() {
        TaskTypeClassifier classifier = new TaskTypeClassifier();
        WorkflowSchema workflow = createWorkflowWithNodes(0, 1, 1);

        TaskTypeClassifier.ClassificationResult result = classifier.classify(workflow);

        assertEquals(TaskType.DATA_PROCESSING, result.getTaskType());
    }

    private WorkflowSchema createWorkflowWithNodes(int agentCount, int sourceCount, int outputCount) {
        WorkflowSchema workflow = new WorkflowSchema();
        java.util.List<Node> nodes = new java.util.ArrayList<>();

        for (int i = 0; i < sourceCount; i++) {
            nodes.add(createNode("source-" + i, "source"));
        }
        for (int i = 0; i < agentCount; i++) {
            nodes.add(createNode("agent-" + i, "agent"));
        }
        for (int i = 0; i < outputCount; i++) {
            nodes.add(createNode("output-" + i, "output"));
        }

        workflow.setNodes(nodes);
        return workflow;
    }

    private WorkflowSchema createWorkflowWithConditionNodes() {
        WorkflowSchema workflow = new WorkflowSchema();
        java.util.List<Node> nodes = new java.util.ArrayList<>();

        nodes.add(createNode("source-1", "source"));
        nodes.add(createNode("condition-1", "condition"));
        nodes.add(createNode("agent-1", "agent"));
        nodes.add(createNode("output-1", "output"));

        workflow.setNodes(nodes);
        return workflow;
    }

    private WorkflowSchema createWorkflowWithPrompt(String prompt) {
        WorkflowSchema workflow = new WorkflowSchema();
        java.util.List<Node> nodes = new java.util.ArrayList<>();

        nodes.add(createNode("source-1", "source"));

        Node agentNode = createNode("agent-1", "agent");
        Node.NodeData data = new Node.NodeData();
        data.setSystemPrompt(prompt);
        agentNode.setData(data);

        nodes.add(agentNode);
        // Note: NO output node - this allows prompt-based classification to work

        workflow.setNodes(nodes);
        return workflow;
    }

    private Node createNode(String id, String type) {
        Node node = new Node();
        node.setId(id);
        node.setType(type);
        return node;
    }
}
