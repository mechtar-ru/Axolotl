package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.Node;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

/**
 * Unit tests for NodeFactory.
 */
class NodeFactoryTest {

    @Test
    void shouldGenerateNodeFromPattern() {
        NodeFactory factory = new NodeFactory();
        
        PatternDetector.Pattern pattern = new PatternDetector.Pattern(
            Arrays.asList("source", "agent", "output")
        );
        
        Node node = factory.generateNode(pattern);
        
        assertNotNull(node);
        assertEquals("generated", node.getType());
        assertTrue(node.getName().contains("Pattern"));
    }

    @Test
    void shouldRetrievePatternForGeneratedNode() {
        NodeFactory factory = new NodeFactory();
        
        PatternDetector.Pattern pattern = new PatternDetector.Pattern(
            Arrays.asList("agent", "output")
        );
        
        Node node = factory.generateNode(pattern);
        PatternDetector.Pattern retrieved = factory.getPatternForNode(node.getId());
        
        assertNotNull(retrieved);
        assertEquals(pattern.getSequence(), retrieved.getSequence());
    }

    @Test
    void shouldGetAllGeneratedNodes() {
        NodeFactory factory = new NodeFactory();
        
        PatternDetector.Pattern pattern1 = new PatternDetector.Pattern(
            Arrays.asList("source", "agent")
        );
        PatternDetector.Pattern pattern2 = new PatternDetector.Pattern(
            Arrays.asList("agent", "output")
        );
        
        factory.generateNode(pattern1);
        factory.generateNode(pattern2);
        
        Map<String, PatternDetector.Pattern> all = factory.getAllGeneratedNodes();
        assertEquals(2, all.size());
    }

    @Test
    void shouldClearGeneratedNodes() {
        NodeFactory factory = new NodeFactory();
        
        PatternDetector.Pattern pattern = new PatternDetector.Pattern(
            Arrays.asList("source", "output")
        );
        factory.generateNode(pattern);
        
        factory.clear();
        
        Map<String, PatternDetector.Pattern> all = factory.getAllGeneratedNodes();
        assertTrue(all.isEmpty());
    }

    @Test
    void shouldGenerateUniqueNodeIds() {
        NodeFactory factory = new NodeFactory();
        
        PatternDetector.Pattern pattern = new PatternDetector.Pattern(
            Arrays.asList("agent")
        );
        
        Node node1 = factory.generateNode(pattern);
        Node node2 = factory.generateNode(pattern);
        
        assertNotEquals(node1.getId(), node2.getId());
    }
}
