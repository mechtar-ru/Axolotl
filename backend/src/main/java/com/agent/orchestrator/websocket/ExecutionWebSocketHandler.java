package com.agent.orchestrator.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ExecutionWebSocketHandler extends TextWebSocketHandler {
    
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String schemaId = getSchemaIdFromSession(session);
        if (schemaId != null) {
            sessions.put(schemaId, session);
            System.out.println("🔌 WebSocket подключен для схемы: " + schemaId + " (session: " + session.getId() + ", URI: " + session.getUri() + ")");
        } else {
            System.out.println("❌ WebSocket подключен без schemaId: " + session.getId() + ", URI: " + session.getUri());
        }
    }
    
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        System.out.println("📨 WebSocket сообщение: " + message.getPayload());
    }
    
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String schemaId = getSchemaIdFromSession(session);
        if (schemaId != null) {
            sessions.remove(schemaId);
            System.out.println("🔌 WebSocket отключен для схемы: " + schemaId);
        }
    }
    
    private String getSchemaIdFromSession(WebSocketSession session) {
        String query = session.getUri().getQuery();
        if (query != null && query.startsWith("schemaId=")) {
            return query.substring(9);
        }
        return null;
    }
    
    private void sendMessage(String schemaId, String jsonMessage) {
        WebSocketSession session = sessions.get(schemaId);
        if (session != null && session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(jsonMessage));
                System.out.println("📤 Отправлено WS сообщение для схемы " + schemaId + ": " + jsonMessage);
            } catch (IOException e) {
                System.err.println("Ошибка отправки WebSocket сообщения: " + e.getMessage());
            }
        } else {
            System.out.println("⚠️ Нет активной WS сессии для схемы " + schemaId);
        }
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\b", "\\b")
                .replace("\f", "\\f");
    }
    
    public void sendProgress(String schemaId, String nodeId, String status, int progress, String message) {
        String json = String.format(
            "{\"type\":\"progress\",\"schemaId\":\"%s\",\"nodeId\":\"%s\",\"status\":\"%s\",\"progress\":%d,\"message\":\"%s\"}",
            escapeJson(schemaId),
            escapeJson(nodeId),
            escapeJson(status),
            progress,
            escapeJson(message)
        );
        sendMessage(schemaId, json);
        System.out.println("📊 Прогресс [" + schemaId + "/" + nodeId + "]: " + status + " - " + progress + "% - " + message);
    }

    public void sendResult(String schemaId, String nodeId, String result) {
        String json = String.format(
            "{\"type\":\"result\",\"schemaId\":\"%s\",\"nodeId\":\"%s\",\"result\":\"%s\"}",
            escapeJson(schemaId),
            escapeJson(nodeId),
            escapeJson(result)
        );
        sendMessage(schemaId, json);
        System.out.println("📊 Результат [" + schemaId + "/" + nodeId + "]: " + result);
    }

    public void sendError(String schemaId, String nodeId, String error) {
        String json = String.format(
            "{\"type\":\"error\",\"schemaId\":\"%s\",\"nodeId\":\"%s\",\"error\":\"%s\"}",
            escapeJson(schemaId),
            escapeJson(nodeId),
            escapeJson(error)
        );
        sendMessage(schemaId, json);
        System.out.println("❌ Ошибка [" + schemaId + "/" + nodeId + "]: " + error);
    }
    
    public void sendComplete(String schemaId, long totalTime, int nodesCompleted) {
        String json = String.format(
            "{\"type\":\"complete\",\"schemaId\":\"%s\",\"totalTime\":%d,\"nodesCompleted\":%d}",
            escapeJson(schemaId),
            totalTime,
            nodesCompleted
        );
        sendMessage(schemaId, json);
        System.out.println("✅ Выполнение завершено [" + schemaId + "]: " + totalTime + "мс, узлов: " + nodesCompleted);
    }

    public void sendMetrics(String schemaId, int totalNodes, int completedNodes, long elapsedTime, double nodesPerSecond) {
        String json = String.format(
            "{\"type\":\"metrics\",\"schemaId\":\"%s\",\"totalNodes\":%d,\"completedNodes\":%d,\"elapsedTime\":%d,\"nodesPerSecond\":%.2f}",
            escapeJson(schemaId),
            totalNodes,
            completedNodes,
            elapsedTime,
            nodesPerSecond
        );
        sendMessage(schemaId, json);
        System.out.println("📊 Метрики [" + schemaId + "]: " + completedNodes + "/" + totalNodes + " узлов, " + elapsedTime + "мс, " + String.format("%.2f", nodesPerSecond) + " уз/с");
    }

    public void sendLog(String schemaId, String level, String message, String nodeId) {
        String json = String.format(
            "{\"type\":\"log\",\"schemaId\":\"%s\",\"level\":\"%s\",\"message\":\"%s\",\"nodeId\":\"%s\",\"timestamp\":%d}",
            escapeJson(schemaId),
            escapeJson(level),
            escapeJson(message),
            escapeJson(nodeId != null ? nodeId : ""),
            System.currentTimeMillis()
        );
        sendMessage(schemaId, json);
        System.out.println("📝 Лог [" + schemaId + "][" + level + "]: " + message + (nodeId != null ? " (узел: " + nodeId + ")" : ""));
    }
}
