package com.agent.orchestrator.model;

import java.util.Set;
import java.util.HashSet;

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

    public String getToolId() { return toolId; }
    public void setToolId(String toolId) { this.toolId = toolId; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Set<String> getAllowedPaths() { return allowedPaths; }
    public void setAllowedPaths(Set<String> allowedPaths) { this.allowedPaths = allowedPaths; }

    public Set<String> getBlockedCommands() { return blockedCommands; }
    public void setBlockedCommands(Set<String> blockedCommands) { this.blockedCommands = blockedCommands; }

    public int getMaxCallsPerExecution() { return maxCallsPerExecution; }
    public void setMaxCallsPerExecution(int maxCallsPerExecution) { this.maxCallsPerExecution = maxCallsPerExecution; }

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }

    public boolean isRequiresApproval() { return requiresApproval; }
    public void setRequiresApproval(boolean requiresApproval) { this.requiresApproval = requiresApproval; }

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