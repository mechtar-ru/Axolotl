package com.agent.orchestrator.plugin;

import java.util.*;

/**
 * Configuration for the Axolotl plugin system.
 * Loaded from application.yml axolotl.plugins.* namespace.
 */
public class PluginConfig {

    private String bridgePath = "plugins/plugin-bridge.js";
    private String bunPath = "bun";
    private int startupTimeoutMs = 15_000;
    private int requestTimeoutMs = 30_000;
    private int maxRestartAttempts = 3;
    private long restartBackoffMs = 1_000;
    private boolean autoUpdateEnabled = true;
    private boolean autoUpdateOnStart = true;
    private List<PluginDefinition> plugins = new ArrayList<>();
    private Map<String, Object> pluginConfigs = new HashMap<>();

    // ─── Getters & Setters ───

    public String getBridgePath() { return bridgePath; }
    public void setBridgePath(String bridgePath) { this.bridgePath = bridgePath; }

    public String getBunPath() { return bunPath; }
    public void setBunPath(String bunPath) { this.bunPath = bunPath; }

    public int getStartupTimeoutMs() { return startupTimeoutMs; }
    public void setStartupTimeoutMs(int startupTimeoutMs) { this.startupTimeoutMs = startupTimeoutMs; }

    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public void setRequestTimeoutMs(int requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }

    public int getMaxRestartAttempts() { return maxRestartAttempts; }
    public void setMaxRestartAttempts(int maxRestartAttempts) { this.maxRestartAttempts = maxRestartAttempts; }

    public long getRestartBackoffMs() { return restartBackoffMs; }
    public void setRestartBackoffMs(long restartBackoffMs) { this.restartBackoffMs = restartBackoffMs; }

    public boolean isAutoUpdateEnabled() { return autoUpdateEnabled; }
    public void setAutoUpdateEnabled(boolean autoUpdateEnabled) { this.autoUpdateEnabled = autoUpdateEnabled; }

    public boolean isAutoUpdateOnStart() { return autoUpdateOnStart; }
    public void setAutoUpdateOnStart(boolean autoUpdateOnStart) { this.autoUpdateOnStart = autoUpdateOnStart; }

    public List<PluginDefinition> getPlugins() { return plugins; }
    public void setPlugins(List<PluginDefinition> plugins) { this.plugins = plugins; }

    public Map<String, Object> getPluginConfigs() { return pluginConfigs; }
    public void setPluginConfigs(Map<String, Object> pluginConfigs) { this.pluginConfigs = pluginConfigs; }

    // ─── Inner types ───

    /** Definition of a single npm plugin to load */
    public static class PluginDefinition {
        private String name;           // npm package name: "@cortexkit/opencode-magic-context"
        private String version = "latest";
        private boolean enabled = true;
        private boolean autoUpdate = true;
        private Map<String, Object> config = new HashMap<>();

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isAutoUpdate() { return autoUpdate; }
        public void setAutoUpdate(boolean autoUpdate) { this.autoUpdate = autoUpdate; }
        public Map<String, Object> getConfig() { return config; }
        public void setConfig(Map<String, Object> config) { this.config = config; }
    }

    /** Health / status of a loaded plugin */
    public static class PluginStatus {
        private String name;
        private String npmPackage;
        private String version;
        private String status;    // "loading", "running", "failed", "stopped"
        private int toolsCount;
        private int agentsCount;
        private String lastError;
        private long uptimeMs;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getNpmPackage() { return npmPackage; }
        public void setNpmPackage(String npmPackage) { this.npmPackage = npmPackage; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public int getToolsCount() { return toolsCount; }
        public void setToolsCount(int toolsCount) { this.toolsCount = toolsCount; }
        public int getAgentsCount() { return agentsCount; }
        public void setAgentsCount(int agentsCount) { this.agentsCount = agentsCount; }
        public String getLastError() { return lastError; }
        public void setLastError(String lastError) { this.lastError = lastError; }
        public long getUptimeMs() { return uptimeMs; }
        public void setUptimeMs(long uptimeMs) { this.uptimeMs = uptimeMs; }
    }
}
