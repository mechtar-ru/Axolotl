package com.agent.orchestrator.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ExecutionWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ExecutionWebSocketHandler.class);

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String schemaId = getSchemaIdFromSession(session);
        if (schemaId != null) {
            sessions.put(schemaId, session);
            log.info("WebSocket подключен для схемы: {} (session: {}, URI: {})", schemaId, session.getId(),
                    session.getUri());
        } else {
            log.error("WebSocket подключен без schemaId: {}, URI: {}", session.getId(), session.getUri());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        log.debug("WebSocket сообщение: {}", message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String schemaId = getSchemaIdFromSession(session);
        if (schemaId != null) {
            sessions.remove(schemaId);
            log.info("WebSocket отключен для схемы: {}", schemaId);
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
                log.debug("Отправлено WS сообщение для схемы {}: {}", schemaId, jsonMessage);
            } catch (IOException e) {
                log.error("Ошибка отправки WebSocket сообщения: {}", e.getMessage());
            }
        } else {
            log.warn("Нет активной WS сессии для схемы {}", schemaId);
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

    /** Package-private accessor for testing */
    String escapeJsonPublic(String value) {
        return escapeJson(value);
    }

    public void sendProgress(String schemaId, String nodeId, String status, int progress, String message) {
        String json = String.format(
                "{\"type\":\"progress\",\"schemaId\":\"%s\",\"nodeId\":\"%s\",\"status\":\"%s\",\"progress\":%d,\"message\":\"%s\"}",
                escapeJson(schemaId),
                escapeJson(nodeId),
                escapeJson(status),
                progress,
                escapeJson(message));
        sendMessage(schemaId, json);
        log.info("Прогресс [{}/{}]: {} - {}% - {}", schemaId, nodeId, status, progress, message);
    }

    public void sendResult(String schemaId, String nodeId, String result) {
        String json = String.format(
                "{\"type\":\"result\",\"schemaId\":\"%s\",\"nodeId\":\"%s\",\"result\":\"%s\"}",
                escapeJson(schemaId),
                escapeJson(nodeId),
                escapeJson(result));
        sendMessage(schemaId, json);
        log.info("Результат [{}/{}]: {}", schemaId, nodeId, result);
    }

    public void sendError(String schemaId, String nodeId, String error) {
        String json = String.format(
                "{\"type\":\"error\",\"schemaId\":\"%s\",\"nodeId\":\"%s\",\"error\":\"%s\"}",
                escapeJson(schemaId),
                escapeJson(nodeId),
                escapeJson(error));
        sendMessage(schemaId, json);
        log.error("Ошибка [{}/{}]: {}", schemaId, nodeId, error);
    }

    public void sendComplete(String schemaId, long totalTime, int nodesCompleted) {
        String json = String.format(
                "{\"type\":\"complete\",\"schemaId\":\"%s\",\"totalTime\":%d,\"nodesCompleted\":%d}",
                escapeJson(schemaId),
                totalTime,
                nodesCompleted);
        sendMessage(schemaId, json);
        log.info("Выполнение завершено [{}]: {}мс, узлов: {}", schemaId, totalTime, nodesCompleted);
    }

    public void sendMetrics(String schemaId, int totalNodes, int completedNodes, long elapsedTime,
            double nodesPerSecond) {
        String json = String.format(
                "{\"type\":\"metrics\",\"schemaId\":\"%s\",\"totalNodes\":%d,\"completedNodes\":%d,\"elapsedTime\":%d,\"nodesPerSecond\":%.2f}",
                escapeJson(schemaId),
                totalNodes,
                completedNodes,
                elapsedTime,
                nodesPerSecond);
        sendMessage(schemaId, json);
        log.info("Метрики [{}]: {}/{} узлов, {}мс, {} уз/с", schemaId, completedNodes, totalNodes, elapsedTime,
                String.format("%.2f", nodesPerSecond));
    }

    public void sendLog(String schemaId, String level, String message, String nodeId) {
        String json = String.format(
                "{\"type\":\"log\",\"schemaId\":\"%s\",\"level\":\"%s\",\"message\":\"%s\",\"nodeId\":\"%s\",\"timestamp\":%d}",
                escapeJson(schemaId),
                escapeJson(level),
                escapeJson(message),
                escapeJson(nodeId != null ? nodeId : ""),
                System.currentTimeMillis());
        sendMessage(schemaId, json);
        log.debug("Лог [{}][{}]: {}{}", schemaId, level, message, nodeId != null ? " (узел: " + nodeId + ")" : "");
    }

    public void sendNodeTime(String schemaId, String nodeId, long durationMs) {
        String json = String.format(
                "{\"type\":\"nodeTime\",\"schemaId\":\"%s\",\"nodeId\":\"%s\",\"durationMs\":%d}",
                escapeJson(schemaId),
                escapeJson(nodeId),
                durationMs);
        sendMessage(schemaId, json);
    }

    public void sendNodeBlocked(String schemaId, String nodeId, int attemptCount, String message) {
        String json = String.format(
                "{\"type\":\"nodeBlocked\",\"schemaId\":\"%s\",\"nodeId\":\"%s\",\"attemptCount\":%d,\"message\":\"%s\"}",
                escapeJson(schemaId),
                escapeJson(nodeId),
                attemptCount,
                escapeJson(message));
        sendMessage(schemaId, json);
    }

    public void sendToken(String schemaId, String nodeId, String token) {
        String json = String.format(
                "{\"type\":\"token\",\"schemaId\":\"%s\",\"nodeId\":\"%s\",\"token\":\"%s\"}",
                escapeJson(schemaId),
                escapeJson(nodeId),
                escapeJson(token));
        sendMessage(schemaId, json);
    }

    public void sendPlanUpdated(String workspaceId, Object plan) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper planMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            planMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            String planJson = planMapper.writeValueAsString(plan);
            String json = String.format(
                    "{\"type\":\"plan_updated\",\"workspaceId\":\"%s\",\"plan\":%s}",
                    escapeJson(workspaceId),
                    planJson);
            // Broadcast to all sessions (plan is workspace-wide)
            for (var entry : sessions.entrySet()) {
                WebSocketSession session = entry.getValue();
                if (session != null && session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
            log.info("План обновлён для workspace: {}", workspaceId);
        } catch (Exception e) {
            log.error("Ошибка отправки plan_updated: {}", e.getMessage());
        }
    }

    public void sendWaveUpdate(String schemaId, int waveNumber, List<String> nodeIds, String status) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String nodesJson = mapper.writeValueAsString(nodeIds);
            String json = String.format(
                    "{\"type\":\"wave\",\"schemaId\":\"%s\",\"waveNumber\":%d,\"nodeIds\":%s,\"status\":\"%s\"}",
                    escapeJson(schemaId),
                    waveNumber,
                    nodesJson,
                    escapeJson(status));
            sendMessage(schemaId, json);
            log.info("Волна {} [{}]: {} ({} узлов)", waveNumber, schemaId, status, nodeIds.size());
        } catch (Exception e) {
            log.error("Ошибка отправки wave: {}", e.getMessage());
        }
    }
}
