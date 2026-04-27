package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

/**
 * Unit tests for WorkflowPruner.
 */
class WorkflowPrunerTest {

    @Test
    void shouldPreviewPruning() {
        WorkflowSchema workflow = createLinearWorkflow();
        List<String> nodesToRemove = Arrays.asList("node-2"); // Middle node
        
        WorkflowPruner pruner = new WorkflowPruner();
        List<String> preview = pruner.previewPruning(workflow, nodesToRemove);
        
        assertEquals(1, preview.size());
        assertTrue(preview.contains("node-2"));
    }

    @Test
    void shouldPruneAndReconnectEdges() {
        WorkflowSchema workflow = createLinearWorkflow();
        // node-2 is between node-1 and node-3
        
        WorkflowPruner pruner = new WorkflowPruner();
        List<String> nodesToRemove = Arrays.asList("node-2");
        WorkflowSchema pruned = pruner.pruneWorkflow(workflow, nodesToRemove);
        
        // Node-2 should be removed
        assertEquals(2, pruned.getNodes().size());
        assertFalse(pruned.getNodes().stream().anyMatch(n -> n.getId().equals("node-2")));
        
        // Edges should be reconnected: node-1 -> node-3
        assertTrue(pruned.getEdges().stream()
            .anyMatch(e -> e.getSource().equals("node-1") && e.getTarget().equals("node-3")));
    }

    @Test
    void shouldNotRemoveNonexistentNode() {
        WorkflowSchema workflow = createLinearWorkflow();
        List<String> nodesToRemove = Arrays.asList("nonexistent");
        
        WorkflowPruner pruner = new WorkflowPruner();
        List<String> preview = pruner.previewPruning(workflow, nodesToRemove);
        
        assertTrue(preview.isEmpty());
    }

    @Test
    void shouldTrackPruningHistory() {
        WorkflowSchema workflow = createLinearWorkflow();
        WorkflowPruner pruner = new WorkflowPruner();
        
        pruner.pruneWorkflow(workflow, Arrays.asList("node-2"));
        List<String> history = pruner.getPruningHistory();
        
        assertEquals(1, history.size());
        assertTrue(history.get(0).contains("node-2"));
    }

    private WorkflowSchema createLinearWorkflow() {
        WorkflowSchema schema = new WorkflowSchema();
        
        Node node1 = new Node();
        node1.setId("node-1");
        node1.setType("source");
        
        Node node2 = new Node();
        node2.setId("node-2");
        node2.setType("agent");
        
        Node node3 = new Node();
        node3.setId("node-3");
        node3.setType("output");
        
        schema.setNodes(Arrays.asList(node1, node2, node3));
        
        Edge edge1 = new Edge();
        edge1.setId("edge-1");
        edge1.setSource("node-1");
        edge1.setTarget("node-2");
        
        Edge edge2 = new Edge();
        edge2.setId("edge-2");
        edge2.setSource("node-2");
        edge2.setTarget("node-3");
        
        schema.setEdges(Arrays.asList(edge1, edge2));
        
        return schema;
    }
}
