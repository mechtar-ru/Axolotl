package com.agent.orchestrator.analytics;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

public class MultiAgentReasonerTest {
    
    @Test
    void testAddAgent() {
        MultiAgentReasoner reasoner = new MultiAgentReasoner();
        
        reasoner.addAgent("agent1", Map.of("model", "gpt-4"));
        
        var state = reasoner.getAgentState("agent1");
        assertNotNull(state);
        assertEquals("agent1", state.getAgentId());
    }
    
    @Test
    void testFacilitateSequentialInteraction() {
        MultiAgentReasoner reasoner = new MultiAgentReasoner();
        
        List<String> agents = Arrays.asList("agent1", "agent2", "agent3");
        var order = reasoner.facilitateInteraction(
            MultiAgentReasoner.InteractionType.SEQUENTIAL, agents);
        
        assertEquals(3, order.size());
        assertEquals("agent1", order.get(0));
    }
    
    @Test
    void testFacilitateParallelInteraction() {
        MultiAgentReasoner reasoner = new MultiAgentReasoner();
        
        List<String> agents = Arrays.asList("agent1", "agent2");
        var order = reasoner.facilitateInteraction(
            MultiAgentReasoner.InteractionType.PARALLEL, agents);
        
        assertEquals(2, order.size());
    }
    
    @Test
    void testWriteToBlackboard() {
        MultiAgentReasoner reasoner = new MultiAgentReasoner();
        
        reasoner.writeToBlackboard("key1", "value1");
        
        assertEquals("value1", reasoner.readFromBlackboard("key1"));
    }
    
    @Test
    void testReadFromBlackboardMissing() {
        MultiAgentReasoner reasoner = new MultiAgentReasoner();
        
        assertNull(reasoner.readFromBlackboard("missing"));
    }
    
    @Test
    void testGetFullBlackboard() {
        MultiAgentReasoner reasoner = new MultiAgentReasoner();
        
        reasoner.writeToBlackboard("key1", "value1");
        reasoner.writeToBlackboard("key2", 42);
        
        var blackboard = reasoner.getFullBlackboard();
        
        assertEquals(2, blackboard.size());
    }
    
    @Test
    void testClearBlackboard() {
        MultiAgentReasoner reasoner = new MultiAgentReasoner();
        
        reasoner.writeToBlackboard("key1", "value1");
        reasoner.clearBlackboard();
        
        assertTrue(reasoner.getFullBlackboard().isEmpty());
    }
    
    @Test
    void testPassMessage() {
        MultiAgentReasoner reasoner = new MultiAgentReasoner();
        
        reasoner.addAgent("agent1", null);
        reasoner.addAgent("agent2", null);
        reasoner.passMessage("agent1", "agent2", "Hello");
        
        var recipientState = reasoner.getAgentState("agent2");
        assertEquals("RECEIVED_MESSAGE", recipientState.getStatus());
    }
    
    @Test
    void testPassMessageInvalidAgent() {
        MultiAgentReasoner reasoner = new MultiAgentReasoner();
        
        reasoner.addAgent("agent1", null);
        reasoner.passMessage("agent1", "nonexistent", "Hello");
        
        // Should not throw, just no-op
    }
    
    @Test
    void testUpdateAgentState() {
        MultiAgentReasoner reasoner = new MultiAgentReasoner();
        
        reasoner.addAgent("agent1", null);
        reasoner.updateAgentState("agent1", "PROCESSING");
        
        var state = reasoner.getAgentState("agent1");
        assertEquals("PROCESSING", state.getStatus());
    }
    
    @Test
    void testUpdateAgentContext() {
        MultiAgentReasoner reasoner = new MultiAgentReasoner();
        
        reasoner.addAgent("agent1", null);
        reasoner.updateAgentContext("agent1", "task", "summarization");
        
        var state = reasoner.getAgentState("agent1");
        assertEquals("summarization", state.getContext().get("task"));
    }
    
    @Test
    void testGetRegisteredAgents() {
        MultiAgentReasoner reasoner = new MultiAgentReasoner();
        
        reasoner.addAgent("agent1", null);
        reasoner.addAgent("agent2", null);
        
        var agents = reasoner.getRegisteredAgents();
        
        assertEquals(2, agents.size());
    }
    
    @Test
    void testGetAgentMessages() {
        MultiAgentReasoner reasoner = new MultiAgentReasoner();
        
        reasoner.addAgent("agent1", null);
        reasoner.addAgent("agent2", null);
        reasoner.passMessage("agent1", "agent2", "Hello");
        
        var messages = reasoner.getAgentMessages("agent1");
        
        assertEquals(1, messages.size());
    }
}