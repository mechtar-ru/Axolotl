package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.Edge;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

public class WorkflowDocumenterTest {
    private final WorkflowDocumenter documenter = new WorkflowDocumenter();

    @Test
    void testDocumentEmptyWorkflow() {
        WorkflowSchema schema = new WorkflowSchema();
        String doc = documenter.documentWorkflow(schema);
        assertTrue(doc.contains("Untitled"));
        assertTrue(doc.contains("Nodes: 0"));
    }

    @Test
    void testDocumentLinearWorkflow() {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setName("Test Workflow");
        Node source = new Node(); source.setId("n1"); source.setType("source");
        Node agent = new Node(); agent.setId("n2"); agent.setType("agent");
        Node output = new Node(); output.setId("n3"); output.setType("output");
        schema.setNodes(List.of(source, agent, output));

        Edge e1 = new Edge(); e1.setSource("n1"); e1.setTarget("n2");
        Edge e2 = new Edge(); e2.setSource("n2"); e2.setTarget("n3");
        schema.setEdges(List.of(e1, e2));

        String doc = documenter.documentWorkflow(schema);
        assertTrue(doc.contains("Test Workflow"));
        assertTrue(doc.contains("Source node (id: n1)"));
        assertTrue(doc.contains("Agent node (id: n2)"));
        assertTrue(doc.contains("source (n1) → agent (n2)"));
    }
}
