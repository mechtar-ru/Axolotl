package com.agent.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import lombok.Data;

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
        IDLE, RUNNING, COMPLETED, FAILED, BLOCKED, AWAITING_APPROVAL;

        @JsonCreator
        public static NodeStatus fromString(String value) {
            if (value == null) return IDLE;
            // Игнорируем регистр при парсинге
            return switch (value.toUpperCase()) {
                case "RUNNING" -> RUNNING;
                case "COMPLETED" -> COMPLETED;
                case "FAILED" -> FAILED;
                case "BLOCKED" -> BLOCKED;
                case "AWAITING_APPROVAL" -> AWAITING_APPROVAL;
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
    
    @Data
    public static class NodeData {
        private String systemPrompt;
        private String userPrompt;
        private String sourceData;
        private String model;
        private Map<String, Object> config;
        private List<Message> messages;
        private String result;
        private String condition;

        // Геттеры и сеттеры
        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

        public String getUserPrompt() { return userPrompt; }
        public void setUserPrompt(String userPrompt) { this.userPrompt = userPrompt; }

        public String getSourceData() { return sourceData; }
        public void setSourceData(String sourceData) { this.sourceData = sourceData; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public Map<String, Object> getConfig() { return config; }
        public void setConfig(Map<String, Object> config) { this.config = config; }

        public List<Message> getMessages() { return messages; }
        public void setMessages(List<Message> messages) { this.messages = messages; }

        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }

        public String getCondition() { return condition; }
        public void setCondition(String condition) { this.condition = condition; }

        private String loopCondition;
        private int maxIterations;
        private String subagentSchemaId;
        private Map<String, String> inputMapping;
        private Map<String, String> outputMapping;

        // Transform node fields
        private List<TransformStep> transforms;
        private String fallbackValue;
        private List<TransformRoute> routes;

        // Tool-enabled agent fields
        private String agentType;
        private List<String> enabledTools;
        private List<ToolPermission> toolPermissions;
        private int maxToolCalls;
        private Integer timeoutSeconds;
        private Integer contextBudgetTokens;

        public Integer getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

        public Integer getContextBudgetTokens() { return contextBudgetTokens; }
        public void setContextBudgetTokens(Integer contextBudgetTokens) { this.contextBudgetTokens = contextBudgetTokens; }

        public String getAgentType() { return agentType; }
        public void setAgentType(String agentType) { this.agentType = agentType; }

        public List<String> getEnabledTools() { return enabledTools; }
        public void setEnabledTools(List<String> enabledTools) { this.enabledTools = enabledTools; }

        public List<ToolPermission> getToolPermissions() { return toolPermissions; }
        public void setToolPermissions(List<ToolPermission> toolPermissions) { this.toolPermissions = toolPermissions; }

        public int getMaxToolCalls() { return maxToolCalls; }
        public void setMaxToolCalls(int maxToolCalls) { this.maxToolCalls = maxToolCalls; }

        public String getLoopCondition() { return loopCondition; }
        public void setLoopCondition(String loopCondition) { this.loopCondition = loopCondition; }

        public int getMaxIterations() { return maxIterations; }
        public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }

        public String getSubagentSchemaId() { return subagentSchemaId; }
        public void setSubagentSchemaId(String subagentSchemaId) { this.subagentSchemaId = subagentSchemaId; }

        public Map<String, String> getInputMapping() { return inputMapping; }
        public void setInputMapping(Map<String, String> inputMapping) { this.inputMapping = inputMapping; }

        public Map<String, String> getOutputMapping() { return outputMapping; }
        public void setOutputMapping(Map<String, String> outputMapping) { this.outputMapping = outputMapping; }

        public List<TransformStep> getTransforms() { return transforms; }
        public void setTransforms(List<TransformStep> transforms) { this.transforms = transforms; }

        public String getFallbackValue() { return fallbackValue; }
        public void setFallbackValue(String fallbackValue) { this.fallbackValue = fallbackValue; }

        public List<TransformRoute> getRoutes() { return routes; }
        public void setRoutes(List<TransformRoute> routes) { this.routes = routes; }
    }

    @Data
    public static class TransformStep {
        private String type;
        private Map<String, Object> config;
    }

    @Data
    public static class TransformRoute {
        private String condition;
        private String targetNodeId;
        private String targetPort;
    }
    
    @Data
    public static class Message {
        private String role;
        private String content;
        private long timestamp;

        public Message() {}

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
            this.timestamp = System.currentTimeMillis();
        }
    }
}
