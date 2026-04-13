package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Agent;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class OpenClawClient {
    private static final Logger log = LoggerFactory.getLogger(OpenClawClient.class);
    
    public String sendMessage(Agent agent, String message, String sessionKey) {
        // Временная заглушка
        log.info("Отправка сообщения агенту: {}" + agent.getName() + ": " + message.substring(0, Math.min(50, message.length())) + "...");
        return "Ответ от агента (заглушка): Сообщение получено. Функционал в разработке.";
    }
    
    public boolean healthCheck(Agent agent) {
        return true;
    }
}
