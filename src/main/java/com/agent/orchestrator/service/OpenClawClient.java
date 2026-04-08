package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Agent;
import org.springframework.stereotype.Service;

@Service
public class OpenClawClient {
    
    public String sendMessage(Agent agent, String message, String sessionKey) {
        // Временная заглушка
        System.out.println("📨 Отправка сообщения агенту " + agent.getName() + ": " + message.substring(0, Math.min(50, message.length())) + "...");
        return "Ответ от агента (заглушка): Сообщение получено. Функционал в разработке.";
    }
    
    public boolean healthCheck(Agent agent) {
        return true;
    }
}
