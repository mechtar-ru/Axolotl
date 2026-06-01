package com.agent.orchestrator.model;

import java.util.List;
import java.util.Map;

public class Stage {
    private String id;
    private String name;
    private String nodeType; // "source" | "review" | "agent" | "verifier" | "output" | "transform" | "custom"
    private String subagentSchemaId; // for "custom" type — delegate to another schema
    private String model;
    private List<String> fallbackModels;
    private String systemPrompt;
    private String userPrompt;
    private Map<String, Object> config;
    private List<String> dependencies; // stage IDs this stage depends on
    private Map<String, String> inputMapping; // source stage output field → this stage input field
    private Map<String, String> outputMapping; // this stage output field → target stage input field
    private String condition; // optional SpEL/JS condition for conditional execution
    private String loopCondition;
    private int maxIterations;
    private int maxRetries;
    private long timeoutMs;
    private boolean parallel; // if true, runs nodes within this stage in parallel
    private List<String> enabledTools;
    private String agentType;

    // UI layout
    private double positionX;
    private double positionY;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }

    public String getSubagentSchemaId() { return subagentSchemaId; }
    public void setSubagentSchemaId(String subagentSchemaId) { this.subagentSchemaId = subagentSchemaId; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public List<String> getFallbackModels() { return fallbackModels; }
    public void setFallbackModels(List<String> fallbackModels) { this.fallbackModels = fallbackModels; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public String getUserPrompt() { return userPrompt; }
    public void setUserPrompt(String userPrompt) { this.userPrompt = userPrompt; }

    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }

    public List<String> getDependencies() { return dependencies; }
    public void setDependencies(List<String> dependencies) { this.dependencies = dependencies; }

    public Map<String, String> getInputMapping() { return inputMapping; }
    public void setInputMapping(Map<String, String> inputMapping) { this.inputMapping = inputMapping; }

    public Map<String, String> getOutputMapping() { return outputMapping; }
    public void setOutputMapping(Map<String, String> outputMapping) { this.outputMapping = outputMapping; }

    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }

    public String getLoopCondition() { return loopCondition; }
    public void setLoopCondition(String loopCondition) { this.loopCondition = loopCondition; }

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }

    public boolean isParallel() { return parallel; }
    public void setParallel(boolean parallel) { this.parallel = parallel; }

    public List<String> getEnabledTools() { return enabledTools; }
    public void setEnabledTools(List<String> enabledTools) { this.enabledTools = enabledTools; }

    public String getAgentType() { return agentType; }
    public void setAgentType(String agentType) { this.agentType = agentType; }

    public double getPositionX() { return positionX; }
    public void setPositionX(double positionX) { this.positionX = positionX; }

    public double getPositionY() { return positionY; }
    public void setPositionY(double positionY) { this.positionY = positionY; }
}
