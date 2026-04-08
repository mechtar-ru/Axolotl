package com.agent.orchestrator.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ExecutionWebSocketHandler extends TextWebSocketHandler {
    
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        System.out.println("🔌 WebSocket подключен: " + session.getId());
    }
    
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        System.out.println("📨 WebSocket сообщение: " + message.getPayload());
    }
    
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        System.out.println("🔌 WebSocket отключен: " + session.getId());
    }
    
    public void sendProgress(String schemaId, String nodeId, String status, int progress, String message) {
        System.out.println("📊 Прогресс [" + schemaId + "/" + nodeId + "]: " + status + " - " + progress + "% - " + message);
        // Здесь будет отправка через WebSocket
    }
    
    public void sendResult(String schemaId, String nodeId, String result) {
        System.out.println("📊 Результат [" + schemaId + "/" + nodeId + "]: " + result);
    }
}
