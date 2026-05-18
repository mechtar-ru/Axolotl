package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Agent;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentService {
    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    
    private final OpenClawClient openClawClient;
    private final Map<String, Agent> agents = new ConcurrentHashMap<>();
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    
    public AgentService(OpenClawClient openClawClient) {
        this.openClawClient = openClawClient;
    }
    
    @PostConstruct
    public void init() {
        // Добавляем тестового агента по умолчанию
        Agent defaultAgent = new Agent();
        defaultAgent.setId("local");
        defaultAgent.setName("Локальный агент");
        defaultAgent.setEmoji("🦀");
        Agent.ConnectionInfo conn = new Agent.ConnectionInfo();
        conn.setType("local");
        conn.setUrl("http://localhost:18789");
        conn.setTimeout(30);
        defaultAgent.setConnection(conn);
        agents.put("local", defaultAgent);
        log.info("Добавлен агент: {}", defaultAgent.getName());

        // Добавляем удалённого агента
        Agent remoteAgent = new Agent();
        remoteAgent.setId("remote");
        remoteAgent.setName("Удалённый агент");
        remoteAgent.setEmoji("🚀");
        Agent.ConnectionInfo remoteConn = new Agent.ConnectionInfo();
        remoteConn.setType("remote");
        remoteConn.setUrl("https://your-server.com:18789");
        remoteConn.setTimeout(60);
        remoteAgent.setConnection(remoteConn);
        agents.put("remote", remoteAgent);
        log.info("Добавлен агент: {}", remoteAgent.getName());
    }
    
    public List<Agent> getAllAgents() {
        return new ArrayList<>(agents.values());
    }
    
    public Agent getAgent(String id) {
        return agents.get(id);
    }
    
    public String sendMessage(String agentId, String message, String sessionKey) {
        Agent agent = agents.get(agentId);
        if (agent == null) {
            return "Агент не найден: " + agentId;
        }
        
        String effectiveSessionKey = sessionKey != null ? sessionKey : sessions.get(agentId);
        String response = openClawClient.sendMessage(agent, message, effectiveSessionKey);
        
        if (effectiveSessionKey == null && response != null && !response.startsWith("Ошибка")) {
            sessions.put(agentId, "session-" + agentId + "-" + System.currentTimeMillis());
        }
        
        return response;
    }
    
    public String getSessionKey(String agentId, String currentKey) {
        if (currentKey != null) return currentKey;
        return sessions.get(agentId);
    }
    
    public boolean isAgentAlive(String agentId) {
        Agent agent = agents.get(agentId);
        if (agent == null) return false;
        return openClawClient.healthCheck(agent);
    }
    
    public Agent addAgent(Agent agent) {
        agents.put(agent.getId(), agent);
        return agent;
    }
    
    public void removeAgent(String id) {
        agents.remove(id);
        sessions.remove(id);
    }
}
