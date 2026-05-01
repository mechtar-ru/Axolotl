package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.Edge;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.ArrayList;

public class WorkflowSelfAnalyzerTest {
    private final WorkflowSelfAnalyzer analyzer = new WorkflowSelfAnalyzer();

    @Test
    void testEmptyWorkflow() {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setNodes(new ArrayList<>());
        schema.setEdges(new ArrayList<>());
        WorkflowSelfAnalyzer.WorkflowAnalysisReport report = analyzer.analyze(schema);
        assertEquals(0, report.getNodeCount());
        assertEquals(0, report.getEdgeCount());
        assertFalse(report.hasCycles());
        assertTrue(report.getBottlenecks().isEmpty());
        assertTrue(report.getIsolatedNodes().isEmpty());
    }

    @Test
    void testLinearWorkflow() {
        WorkflowSchema schema = new WorkflowSchema();
        Node source = new Node(); source.setId("n1"); source.setType("source");
        Node agent = new Node(); agent.setId("n2"); agent.setType("agent");
        Node output = new Node(); output.setId("n3"); output.setType("output");
        schema.setNodes(List.of(source, agent, output));

        Edge e1 = new Edge(); e1.setSource("n1"); e1.setTarget("n2");
        Edge e2 = new Edge(); e2.setSource("n2"); e2.setTarget("n3");
        schema.setEdges(List.of(e1, e2));

        WorkflowSelfAnalyzer.WorkflowAnalysisReport report = analyzer.analyze(schema);
        assertEquals(3, report.getNodeCount());
        assertEquals(2, report.getEdgeCount());
        assertFalse(report.hasCycles());
        assertTrue(report.getBottlenecks().isEmpty());
        assertTrue(report.getIsolatedNodes().isEmpty());
        assertEquals(3, report.getDepth());
    }

    @Test
    void testWorkflowWithCycle() {
        WorkflowSchema schema = new WorkflowSchema();
        Node a = new Node(); a.setId("a");
        Node b = new Node(); b.setId("b");
        Node c = new Node(); c.setId("c");
        schema.setNodes(List.of(a, b, c));

        Edge e1 = new Edge(); e1.setSource("a"); e1.setTarget("b");
        Edge e2 = new Edge(); e2.setSource("b"); e2.setTarget("c");
        Edge e3 = new Edge(); e3.setSource("c"); e3.setTarget("a");
        schema.setEdges(List.of(e1, e2, e3));

        WorkflowSelfAnalyzer.WorkflowAnalysisReport report = analyzer.analyze(schema);
        assertTrue(report.hasCycles());
        assertTrue(report.getIssues().contains("Workflow contains cycles"));
    }

    @Test
    void testWorkflowWithBottleneck() {
        WorkflowSchema schema = new WorkflowSchema();
        Node bottleneck = new Node(); bottleneck.setId("b");
        Node n1 = new Node(); n1.setId("n1");
        Node n2 = new Node(); n2.setId("n2");
        Node n3 = new Node(); n3.setId("n3");
        Node n4 = new Node(); n4.setId("n4");
        schema.setNodes(List.of(bottleneck, n1, n2, n3, n4));

        Edge e1 = new Edge(); e1.setSource("n1"); e1.setTarget("b");
        Edge e2 = new Edge(); e2.setSource("n2"); e2.setTarget("b");
        Edge e3 = new Edge(); e3.setSource("b"); e3.setTarget("n3");
        Edge e4 = new Edge(); e4.setSource("b"); e4.setTarget("n4");
        schema.setEdges(List.of(e1, e2, e3, e4));

        WorkflowSelfAnalyzer.WorkflowAnalysisReport report = analyzer.analyze(schema);
        assertTrue(report.getBottlenecks().contains("b"));
    }

    @Test
    void testWorkflowWithIsolatedNode() {
        WorkflowSchema schema = new WorkflowSchema();
        Node isolated = new Node(); isolated.setId("iso");
        Node connected = new Node(); connected.setId("conn");
        schema.setNodes(List.of(isolated, connected));

        Edge e = new Edge(); e.setSource("conn"); e.setTarget("conn");
        schema.setEdges(List.of(e));

        WorkflowSelfAnalyzer.WorkflowAnalysisReport report = analyzer.analyze(schema);
        assertTrue(report.getIsolatedNodes().contains("iso"));
    }
}
