package com.agent.orchestrator.model;

import java.util.Set;
import java.util.HashSet;

import lombok.Data;

@Data
public class ToolPermission {
    private String toolId;
    private boolean enabled;
    private Set<String> allowedPaths;
    private Set<String> blockedCommands;
    private int maxCallsPerExecution;
    private long timeoutMs;
    private boolean requiresApproval;

    public ToolPermission() {
        this.enabled = true;
        this.allowedPaths = new HashSet<>();
        this.blockedCommands = new HashSet<>();
        this.maxCallsPerExecution = 10;
        this.timeoutMs = 30000;
        this.requiresApproval = false;
    }

    public ToolPermission(String toolId) {
        this();
        this.toolId = toolId;
    }


    public boolean allowsCommand(String command) {
        if (blockedCommands.contains(command)) return false;
        for (String blocked : blockedCommands) {
            if (command.contains(blocked)) return false;
        }
        return true;
    }

    public boolean allowsPath(String path) {
        if (allowedPaths.isEmpty()) return true;
        for (String allowed : allowedPaths) {
            if (path.startsWith(allowed)) return true;
        }
        return false;
    }
}