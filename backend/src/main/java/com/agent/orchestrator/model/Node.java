package com.agent.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;
import java.util.Map;

public class Node {
    private String id;
    private String type;
    private String name;
    private Position position;
    private NodeData data;
    private List<String> inputPorts;
    private List<String> outputPorts;
    private NodeStatus status;
    
    public enum NodeStatus {
        IDLE, RUNNING, COMPLETED, FAILED;
        
        @JsonCreator
        public static NodeStatus fromString(String value) {
            if (value == null) return IDLE;
            // Игнорируем регистр при парсинге
            return switch (value.toUpperCase()) {
                case "RUNNING" -> RUNNING;
                case "COMPLETED" -> COMPLETED;
                case "FAILED" -> FAILED;
                default -> IDLE;
            };
        }
        
        @JsonValue
        public String toValue() {
            return this.name();
        }
    }
    
    // Геттеры и сеттеры
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public Position getPosition() { return position; }
    public void setPosition(Position position) { this.position = position; }
    
    public NodeData getData() { return data; }
    public void setData(NodeData data) { this.data = data; }
    
    public List<String> getInputPorts() { return inputPorts; }
    public void setInputPorts(List<String> inputPorts) { this.inputPorts = inputPorts; }
    
    public List<String> getOutputPorts() { return outputPorts; }
    public void setOutputPorts(List<String> outputPorts) { this.outputPorts = outputPorts; }
    
    public NodeStatus getStatus() { return status; }
    public void setStatus(NodeStatus status) { this.status = status; }
    
    public static class Position {
        private double x;
        private double y;
        
        public double getX() { return x; }
        public void setX(double x) { this.x = x; }
        public double getY() { return y; }
        public void setY(double y) { this.y = y; }
    }
    
    public static class NodeData {
        private String systemPrompt;
        private String userPrompt;
        private String model;
        private Map<String, Object> config;
        private List<Message> messages;
        private String result;
        
        // Геттеры и сеттеры
        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
        
        public String getUserPrompt() { return userPrompt; }
        public void setUserPrompt(String userPrompt) { this.userPrompt = userPrompt; }
        
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        
        public Map<String, Object> getConfig() { return config; }
        public void setConfig(Map<String, Object> config) { this.config = config; }
        
        public List<Message> getMessages() { return messages; }
        public void setMessages(List<Message> messages) { this.messages = messages; }
        
        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }
    }
    
    public static class Message {
        private String role;
        private String content;
        private long timestamp;
        
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    }
}
