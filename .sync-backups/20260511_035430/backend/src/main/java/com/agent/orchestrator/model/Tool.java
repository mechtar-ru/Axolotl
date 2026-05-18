package com.agent.orchestrator.model;

import java.util.Map;
import java.util.List;

public class Tool {
    private String id;
    private String name;
    private String description;
    private String inputSchema;
    private ToolCategory category;
    private boolean enabledByDefault;

    public Tool() {}

    public Tool(String id, String name, String description, String inputSchema, ToolCategory category) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.category = category;
        this.enabledByDefault = false;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getInputSchema() { return inputSchema; }
    public void setInputSchema(String inputSchema) { this.inputSchema = inputSchema; }

    public ToolCategory getCategory() { return category; }
    public void setCategory(ToolCategory category) { this.category = category; }

    public boolean isEnabledByDefault() { return enabledByDefault; }
    public void setEnabledByDefault(boolean enabledByDefault) { this.enabledByDefault = enabledByDefault; }

    public enum ToolCategory {
        FILE_SYSTEM,
        EXECUTION,
        MEMORY,
        HTTP,
        GRAPH,
        MCP,
        CUSTOM
    }

    public static class ToolResult {
        private boolean success;
        private String output;
        private String error;
        private long executionTimeMs;

        public ToolResult() {}

        public ToolResult(boolean success, String output, String error) {
            this.success = success;
            this.output = output;
            this.error = error;
        }

        public static ToolResult ok(String output) {
            return new ToolResult(true, output, null);
        }

        public static ToolResult error(String error) {
            return new ToolResult(false, null, error);
        }

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }

        public String getOutput() { return output; }
        public void setOutput(String output) { this.output = output; }

        public String getError() { return error; }
        public void setError(String error) { this.error = error; }

        public long getExecutionTimeMs() { return executionTimeMs; }
        public void setExecutionTimeMs(long executionTimeMs) { this.executionTimeMs = executionTimeMs; }
    }
}
