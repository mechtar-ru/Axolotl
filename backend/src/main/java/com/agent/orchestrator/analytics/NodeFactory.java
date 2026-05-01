package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.Node;
import java.util.*;

/**
 * Generates specialized nodes from detected patterns.
 * 
 * Integration: Generated nodes can be:
 * - Added to workflow canvas (with special type "generated")
 * - Registered in node palette for drag-and-drop
 * - Executed by expanding the encapsulated pattern
 */


public class NodeFactory {
    private final Map<String, PatternDetector.Pattern> generatedNodes = new HashMap<>();

    /**
     * Generates a node from a pattern.
     * Returns a Node with:
     * - type = "generated"
     * - data.config contains the pattern sequence
     */
    public Node generateNode(PatternDetector.Pattern pattern) {
        String nodeId = "generated-" + UUID.randomUUID().toString().substring(0, 8);
        
        Node node = new Node();
        node.setId(nodeId);
        node.setType("generated");
        node.setName("Pattern: " + String.join("-", pattern.getSequence()));
        
        // Store the pattern for this node
        generatedNodes.put(nodeId, pattern);
        
        return node;
    }

    /**
     * Gets the pattern for a generated node.
     */
    public PatternDetector.Pattern getPatternForNode(String nodeId) {
        return generatedNodes.get(nodeId);
    }

    /**
     * Gets all generated node patterns.
     */
    public Map<String, PatternDetector.Pattern> getAllGeneratedNodes() {
        return new HashMap<>(generatedNodes);
    }

    /**
     * Clears all generated nodes.
     */
    public void clear() {
        generatedNodes.clear();
    }
}
