package com.agent.orchestrator.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class ExecutionWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ExecutionWebSocketHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Map<String, List<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String schemaId = getSchemaIdFromSession(session);
        if (schemaId != null) {
            sessions.computeIfAbsent(schemaId, k -> new CopyOnWriteArrayList<>()).add(session);
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
            List<WebSocketSession> sessionList = sessions.get(schemaId);
            if (sessionList != null) {
                sessionList.remove(session);
                if (sessionList.isEmpty()) {
                    sessions.remove(schemaId);
                }
            }
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
        List<WebSocketSession> sessionList = sessions.get(schemaId);
        if (sessionList == null || sessionList.isEmpty()) {
            log.warn("Нет активной WS сессии для схемы {}", schemaId);
            return;
        }
        for (WebSocketSession session : sessionList) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(jsonMessage));
                } catch (IOException e) {
                    log.error("Ошибка отправки WebSocket сообщения: {}", e.getMessage());
                }
            }
        }
    }

    private Map<String, Object> baseMsg(String type, String schemaId) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", type);
        msg.put("schemaId", schemaId);
        return msg;
    }

    private String toJson(Map<String, Object> msg) {
        try {
            return objectMapper.writeValueAsString(msg);
        } catch (Exception e) {
            log.error("JSON serialization failed: {}", e.getMessage());
            return "{}";
        }
    }

    public void sendProgress(String schemaId, String nodeId, String status, int progress, String message) {
        Map<String, Object> msg = baseMsg("progress", schemaId);
        msg.put("nodeId", nodeId);
        msg.put("status", status);
        msg.put("progress", progress);
        msg.put("message", message);
        sendMessage(schemaId, toJson(msg));
        log.debug("Прогресс [{}/{}]: {} - {}% - {}", schemaId, nodeId, status, progress, message);
    }

    public void sendResult(String schemaId, String nodeId, String result) {
        Map<String, Object> msg = baseMsg("result", schemaId);
        msg.put("nodeId", nodeId);
        msg.put("result", result);
        sendMessage(schemaId, toJson(msg));
        log.debug("Результат [{}/{}]: {}", schemaId, nodeId, result.length() > 80 ? result.substring(0, 80) + "..." : result);
    }

    public void sendError(String schemaId, String nodeId, String error) {
        Map<String, Object> msg = baseMsg("error", schemaId);
        msg.put("nodeId", nodeId);
        msg.put("error", error);
        sendMessage(schemaId, toJson(msg));
        log.error("Ошибка [{}/{}]: {}", schemaId, nodeId, error);
    }

    public void sendComplete(String schemaId, long totalTime, int nodesCompleted) {
        Map<String, Object> msg = baseMsg("complete", schemaId);
        msg.put("totalTime", totalTime);
        msg.put("nodesCompleted", nodesCompleted);
        sendMessage(schemaId, toJson(msg));
        log.info("Выполнение завершено [{}]: {}мс, узлов: {}", schemaId, totalTime, nodesCompleted);
    }

    public void sendMetrics(String schemaId, int totalNodes, int completedNodes, long elapsedTime,
            double nodesPerSecond) {
        Map<String, Object> msg = baseMsg("metrics", schemaId);
        msg.put("totalNodes", totalNodes);
        msg.put("completedNodes", completedNodes);
        msg.put("elapsedTime", elapsedTime);
        msg.put("nodesPerSecond", nodesPerSecond);
        sendMessage(schemaId, toJson(msg));
        log.debug("Метрики [{}]: {}/{} узлов, {}мс, {} уз/с", schemaId, completedNodes, totalNodes, elapsedTime,
                String.format("%.2f", nodesPerSecond));
    }

    public void sendLog(String schemaId, String level, String message, String nodeId) {
        Map<String, Object> msg = baseMsg("log", schemaId);
        msg.put("level", level);
        msg.put("message", message);
        msg.put("nodeId", nodeId != null ? nodeId : "");
        msg.put("timestamp", System.currentTimeMillis());
        sendMessage(schemaId, toJson(msg));
        log.debug("Лог [{}][{}]: {}{}", schemaId, level, message, nodeId != null ? " (узел: " + nodeId + ")" : "");
    }

    public void sendNodeTime(String schemaId, String nodeId, long durationMs) {
        Map<String, Object> msg = baseMsg("nodeTime", schemaId);
        msg.put("nodeId", nodeId);
        msg.put("durationMs", durationMs);
        sendMessage(schemaId, toJson(msg));
    }

    public void sendNodeBlocked(String schemaId, String nodeId, int attemptCount, String message) {
        Map<String, Object> msg = baseMsg("nodeBlocked", schemaId);
        msg.put("nodeId", nodeId);
        msg.put("attemptCount", attemptCount);
        msg.put("message", message);
        sendMessage(schemaId, toJson(msg));
    }

    public void sendToken(String schemaId, String nodeId, String token) {
        Map<String, Object> msg = baseMsg("token", schemaId);
        msg.put("nodeId", nodeId);
        msg.put("token", token);
        sendMessage(schemaId, toJson(msg));
    }

    public void sendPlanUpdated(String workspaceId, Object plan) {
        try {
            ObjectMapper planMapper = new ObjectMapper();
            planMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "plan_updated");
            msg.put("workspaceId", workspaceId);
            msg.put("plan", plan);
            String json = objectMapper.writeValueAsString(msg);
            for (var entry : sessions.entrySet()) {
                for (WebSocketSession session : entry.getValue()) {
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(json));
                    }
                }
            }
            log.info("План обновлён для workspace: {}", workspaceId);
        } catch (Exception e) {
            log.error("Ошибка отправки plan_updated: {}", e.getMessage());
        }
    }

    public void sendWaveUpdate(String schemaId, int waveNumber, List<String> nodeIds, String status) {
        Map<String, Object> msg = baseMsg("wave", schemaId);
        msg.put("waveNumber", waveNumber);
        msg.put("nodeIds", nodeIds);
        msg.put("status", status);
        sendMessage(schemaId, toJson(msg));
        log.debug("Волна {} [{}]: {} ({} узлов)", waveNumber, schemaId, status, nodeIds.size());
    }
}
