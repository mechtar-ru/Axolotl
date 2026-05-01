package com.agent.orchestrator.analytics;

import java.util.*;

/**
 * Reasoner for modeling interactions between multiple AI agents in workflows.
 * 
 * Integration points:
 * - addAgent(agentId, agentConfig) - registers an agent
 * - facilitateInteraction(interactionType, agents) - manages agent interaction
 * - manageSharedState(agents, sharedData) - manages shared blackboard
 * - updateAgentStates(agentId, state) - updates agent states
 * 
 * Usage in workflows: Use with multi-agent nodes for negotiation, debate,
 * or pipelined refinement scenarios.
 */


public class MultiAgentReasoner {
    
    public enum InteractionType {
        SEQUENTIAL, PARALLEL, DEBATE, NEGOTIATION, PIPELINED
    }
    
    public static class AgentState {
        public String agentId;
        public Map<String, Object> context;
        public String status;
        
        public AgentState(String agentId, Map<String, Object> context, String status) {
            this.agentId = agentId;
            this.context = context;
            this.status = status;
        }
        
        public String getAgentId() { return agentId; }
        public Map<String, Object> getContext() { return context; }
        public String getStatus() { return status; }
    }
    
    public static class AgentMessage {
        public String fromAgentId;
        public String toAgentId;
        public String content;
        public long timestamp;
        
        public AgentMessage(String fromAgentId, String toAgentId, String content, long timestamp) {
            this.fromAgentId = fromAgentId;
            this.toAgentId = toAgentId;
            this.content = content;
            this.timestamp = timestamp;
        }
        
        public String getFromAgentId() { return fromAgentId; }
        public String getToAgentId() { return toAgentId; }
        public String getContent() { return content; }
        public long getTimestamp() { return timestamp; }
    }
    
    private final Map<String, AgentState> agentStates = new HashMap<>();
    private final Map<String, List<AgentMessage>> messageHistory = new HashMap<>();
    private final Map<String, Object> sharedBlackboard = new HashMap<>();
    
    /**
     * Adds an agent to the reasoning system.
     */
    public void addAgent(String agentId, Map<String, Object> agentConfig) {
        Map<String, Object> context = new HashMap<>();
        if (agentConfig != null) {
            context.putAll(agentConfig);
        }
        
        AgentState state = new AgentState(agentId, context, "IDLE");
        agentStates.put(agentId, state);
        messageHistory.put(agentId, new ArrayList<>());
    }
    
    /**
     * Facilitates interaction between agents based on interaction type.
     */
    public List<String> facilitateInteraction(InteractionType interactionType, List<String> agentIds) {
        List<String> executionOrder = new ArrayList<>();
        
        switch (interactionType) {
            case SEQUENTIAL:
            case PIPELINED:
                executionOrder.addAll(agentIds);
                break;
                
            case PARALLEL:
                executionOrder.addAll(agentIds);
                break;
                
            case DEBATE:
            case NEGOTIATION:
                executionOrder.addAll(agentIds);
                break;
                
            default:
                executionOrder.addAll(agentIds);
        }
        
        return executionOrder;
    }
    
    /**
     * Manages shared blackboard for agent communication.
     */
    public void writeToBlackboard(String key, Object value) {
        sharedBlackboard.put(key, value);
    }
    
    public Object readFromBlackboard(String key) {
        return sharedBlackboard.get(key);
    }
    
    public Map<String, Object> getFullBlackboard() {
        return new HashMap<>(sharedBlackboard);
    }
    
    public void clearBlackboard() {
        sharedBlackboard.clear();
    }
    
    /**
     * Passes a message from one agent to another.
     */
    public void passMessage(String fromAgentId, String toAgentId, String content) {
        if (!agentStates.containsKey(fromAgentId) || !agentStates.containsKey(toAgentId)) {
            return;
        }
        
        AgentMessage message = new AgentMessage(fromAgentId, toAgentId, content, System.currentTimeMillis());
        messageHistory.get(fromAgentId).add(message);
        
        // Update recipient status
        AgentState recipient = agentStates.get(toAgentId);
        recipient.status = "RECEIVED_MESSAGE";
        agentStates.put(toAgentId, recipient);
    }
    
    /**
     * Updates agent state based on interaction.
     */
    public void updateAgentState(String agentId, String newStatus) {
        AgentState current = agentStates.get(agentId);
        if (current == null) {
            return;
        }
        
        current.status = newStatus;
        agentStates.put(agentId, current);
    }
    
    /**
     * Updates agent context.
     */
    public void updateAgentContext(String agentId, String key, Object value) {
        AgentState current = agentStates.get(agentId);
        if (current == null) {
            return;
        }
        
        current.context.put(key, value);
        agentStates.put(agentId, current);
    }
    
    public AgentState getAgentState(String agentId) {
        return agentStates.get(agentId);
    }
    
    public List<AgentMessage> getAgentMessages(String agentId) {
        List<AgentMessage> messages = messageHistory.get(agentId);
        if (messages == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(messages);
    }
    
    public List<String> getRegisteredAgents() {
        return new ArrayList<>(agentStates.keySet());
    }
}