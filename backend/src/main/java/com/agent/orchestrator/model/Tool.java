package com.agent.orchestrator.model;

import java.util.Map;
import java.util.List;

import lombok.Data;

@Data
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


    public enum ToolCategory {
        FILE_SYSTEM,
        EXECUTION,
        MEMORY,
        HTTP,
        GRAPH,
        MCP,
        CUSTOM
    }

    @Data
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


    }
}
