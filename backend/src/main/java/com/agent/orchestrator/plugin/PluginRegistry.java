package com.agent.orchestrator.plugin;

import com.agent.orchestrator.plugin.PluginBridge.PluginException;
import com.agent.orchestrator.service.ToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Central registry for all plugin instances in Axolotl.
 * <p>
 * Manages PluginBridge lifecycle for each configured plugin,
 * connects plugin tools to ToolExecutor, and routes events.
 */
public class PluginRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** npm package name validation: @scope/name or name, alphanumeric + hyphens + dots */
    private static final Pattern NPM_PACKAGE_PATTERN =
            Pattern.compile("^@?[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?(?:/[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?)?$");

    /** Project root must not contain shell metacharacters */
    private static final Pattern SAFE_PATH_PATTERN =
            Pattern.compile("^[a-zA-Z0-9/_.~@ -]+$");

    private final PluginConfig config;
    private final ToolExecutor toolExecutor;
    private final String projectRoot;

    /** Active bridges: plugin name → PluginBridge */
    private final Map<String, PluginBridge> bridges = new ConcurrentHashMap<>();

    /** Tool adapters: plugin name → PluginToolAdapter */
    private final Map<String, PluginToolAdapter> toolAdapters = new ConcurrentHashMap<>();

    /** Status of each plugin */
    private final Map<String, PluginConfig.PluginStatus> statuses = new ConcurrentHashMap<>();

    /** Whether the registry has been initialized */
    private volatile boolean initialized;

    public PluginRegistry(PluginConfig config, ToolExecutor toolExecutor, String projectRoot) {
        this.config = config;
        this.toolExecutor = toolExecutor;
        this.projectRoot = projectRoot;
        validateProjectRoot();
    }

    /**
     * Validate that projectRoot doesn't contain shell metacharacters.
     * Called at construction time; warns if unsafe.
     */
    private void validateProjectRoot() {
        if (projectRoot != null && !SAFE_PATH_PATTERN.matcher(projectRoot).matches()) {
            log.warn("Project root contains potentially unsafe characters: {}", projectRoot);
        }
    }

    // ─── Initialization ───

    /**
     * Initialize and start all enabled plugins.
     * Guarded by config-level enabled check from PluginLifecycleManager.
     */
    public synchronized void initialize() throws PluginException {
        if (initialized) {
            log.warn("Plugin registry already initialized");
            return;
        }
        initialized = true;

        // Resolve bridge JS path
        String bridgeJsPath = resolveBridgeJsPath();

        // Start each configured plugin
        for (PluginConfig.PluginDefinition pluginDef : config.getPlugins()) {
            if (!pluginDef.isEnabled()) {
                log.info("Plugin '{}' is disabled, skipping", pluginDef.getName());
                continue;
            }
            startPlugin(pluginDef, bridgeJsPath);
        }

        log.info("Plugin registry initialized: {} plugin(s) running", bridges.size());
    }

    /**
     * Start a single plugin.
     */
    private synchronized void startPlugin(PluginConfig.PluginDefinition pluginDef, String bridgeJsPath) {
        String pluginName = pluginDef.getName();
        try {
            PluginBridge bridge = new PluginBridge(
                    pluginName,
                    config.getBunPath(),
                    bridgeJsPath,
                    config.getStartupTimeoutMs(),
                    config.getRequestTimeoutMs(),
                    config.getMaxRestartAttempts(),
                    config.getRestartBackoffMs()
            );

            PluginToolAdapter adapter = new PluginToolAdapter(bridge, toolExecutor);

            // Handle incoming requests from plugin (tool/execute)
            bridge.onRequest(msg -> handlePluginRequest(bridge, adapter, msg));

            // Handle notifications from plugin
            bridge.onNotification(msg -> handlePluginNotification(bridge, adapter, msg));

            // Handle plugin logs
            bridge.setLogHandler(msg -> log.debug("[{}] {}", pluginName, msg));

            // Auto-restart on unexpected disconnect
            bridge.setOnDisconnect(() -> {
                log.warn("[{}] Plugin disconnected, attempting auto-restart", pluginName);
                try {
                    // Find and restart the plugin via the registry (outside bridge lock)
                    restartPlugin(pluginName);
                } catch (Exception e) {
                    log.error("[{}] Auto-restart failed: {}", pluginName, e.getMessage());
                }
            });

            // Build init params
            Map<String, Object> initParams = buildInitParams(pluginDef);

            // Start the bridge (blocks until "plugin/ready")
            bridge.start(initParams);

            bridges.put(pluginName, bridge);
            toolAdapters.put(pluginName, adapter);

            // Update status
            PluginConfig.PluginStatus status = new PluginConfig.PluginStatus();
            status.setName(pluginName);
            status.setNpmPackage(pluginName);
            status.setVersion(bridge.getPluginVersion());
            status.setStatus("running");
            status.setToolsCount(adapter.getToolCount());
            status.setUptimeMs(bridge.getUptimeMs());
            statuses.put(pluginName, status);

            log.info("Plugin '{}' started successfully (tools: {}, version: {})",
                    pluginName, adapter.getToolCount(), bridge.getPluginVersion());

        } catch (Exception e) {
            log.error("Failed to start plugin '{}': {}", pluginName, e.getMessage());

            PluginConfig.PluginStatus status = new PluginConfig.PluginStatus();
            status.setName(pluginName);
            status.setNpmPackage(pluginName);
            status.setStatus("failed");
            status.setLastError(e.getMessage());
            statuses.put(pluginName, status);
        }
    }

    // ─── Message Handling ───

    private void handlePluginRequest(PluginBridge bridge, PluginToolAdapter adapter,
                                      PluginMessage.ParsedMessage msg) {
        String method = msg.method();
        if ("tool/execute".equals(method)) {
            adapter.handleIncomingToolRequest(msg);
        } else {
            log.debug("[{}] Unhandled plugin request: {}", bridge.getName(), method);
        }
    }

    private void handlePluginNotification(PluginBridge bridge, PluginToolAdapter adapter,
                                           PluginMessage.ParsedMessage msg) {
        String method = msg.method();
        if ("tool/register".equals(method)) {
            JsonNode params = msg.params();
            if (params != null) {
                adapter.registerSingleTool(params);
            }
        } else if ("agent/register".equals(method)) {
            log.info("[{}] Agent registration not yet implemented", bridge.getName());
        } else if ("plugin/log".equals(method)) {
            // Already handled by PluginBridge's logHandler
        } else {
            log.debug("[{}] Unhandled plugin notification: {}", bridge.getName(), method);
        }
    }

    // ─── Plugin Management ───

    /**
     * Stop a specific plugin.
     */
    public synchronized void stopPlugin(String name) {
        PluginBridge bridge = bridges.get(name);
        if (bridge != null) {
            bridge.stop();
            bridges.remove(name);
            toolAdapters.remove(name);

            PluginConfig.PluginStatus status = statuses.get(name);
            if (status != null) {
                status.setStatus("stopped");
            }
        }
    }

    /**
     * Restart a specific plugin.
     */
    public synchronized void restartPlugin(String name) {
        PluginConfig.PluginDefinition def = config.getPlugins().stream()
                .filter(p -> p.getName().equals(name))
                .findFirst().orElse(null);
        if (def == null) {
            throw new IllegalArgumentException("Unknown plugin: " + name);
        }

        stopPlugin(name);

        // Update package if auto-update is enabled
        if (config.isAutoUpdateEnabled() && def.isAutoUpdate()) {
            updatePluginPackage(def);
        }

        String bridgeJsPath = resolveBridgeJsPath();
        startPlugin(def, bridgeJsPath);
    }

    /**
     * Install a new plugin (npm install + start).
     */
    public PluginConfig.PluginStatus installPlugin(String npmPackage, Map<String, Object> pluginConfig) {
        // Validate npm package name before shell execution
        validateNpmPackageName(npmPackage);

        // 1. Install npm package
        String packageName = installNpmPackage(npmPackage);

        // 2. Add to config & start
        PluginConfig.PluginDefinition def = new PluginConfig.PluginDefinition();
        def.setName(packageName);
        def.setVersion("latest");
        def.setEnabled(true);
        if (pluginConfig != null) {
            def.setConfig(pluginConfig);
        }
        config.getPlugins().add(def);

        String bridgeJsPath = resolveBridgeJsPath();
        startPlugin(def, bridgeJsPath);

        return statuses.get(packageName);
    }

    /**
     * Validate an npm package name to prevent command injection.
     * Allows: @scope/name, name, @scope/name-with-dots.and-hyphens
     */
    private void validateNpmPackageName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("npm package name must not be empty");
        }
        if (!NPM_PACKAGE_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Invalid npm package name: '" + name + "'. " +
                    "Expected format: @scope/name or name (alphanumeric, hyphens, dots, underscores)");
        }
        // Additional safety: reject shell metacharacters
        if (name.contains(";") || name.contains("|") || name.contains("`") || name.contains("$") ||
            name.contains(">") || name.contains("<") || name.contains("&") || name.contains("(") ||
            name.contains(")") || name.contains("{") || name.contains("}") || name.contains("\n")) {
            throw new IllegalArgumentException("npm package name contains unsafe characters: " + name);
        }
    }

    /**
     * Update a plugin's npm package.
     */
    private void updatePluginPackage(PluginConfig.PluginDefinition def) {
        try {
            String pkg = def.getName();
            // Validate before constructing shell command
            validateNpmPackageName(pkg);
            String installCmd = String.format("cd %s/plugins && bun add %s@latest",
                    projectRoot, pkg);
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", installCmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0) {
                log.info("Updated plugin package: {}", def.getName());
            } else {
                log.warn("Failed to update plugin package: {}", def.getName());
            }
        } catch (Exception e) {
            log.warn("Failed to auto-update plugin '{}': {}", def.getName(), e.getMessage());
        }
    }

    /**
     * Install an npm package in the plugins directory.
     */
    private String installNpmPackage(String npmPackage) {
        try {
            String installCmd = String.format("cd %s/plugins && bun add %s",
                    projectRoot, npmPackage);
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", installCmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0) {
                String output = new String(process.getInputStream().readAllBytes());
                throw new PluginException("npm install failed: " + output);
            }
            // Extract the actual package name from package.json
            Path pkgJson = Path.of(projectRoot, "plugins", "package.json");
            if (Files.exists(pkgJson)) {
                String content = Files.readString(pkgJson);
                JsonNode json = MAPPER.readTree(content);
                for (Iterator<Map.Entry<String, JsonNode>> it = json.path("dependencies").fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    if (entry.getKey().equals(npmPackage) || npmPackage.contains(entry.getKey())) {
                        return entry.getKey();
                    }
                }
            }
            return npmPackage;
        } catch (Exception e) {
            throw new PluginException("Failed to install plugin: " + e.getMessage(), e);
        }
    }

    // ─── Status & Listing ───

    public Collection<PluginConfig.PluginStatus> getAllStatuses() {
        for (PluginConfig.PluginStatus status : statuses.values()) {
            PluginBridge bridge = bridges.get(status.getName());
            if (bridge != null) {
                status.setStatus(bridge.isRunning() ? "running" : "stopped");
                status.setUptimeMs(bridge.getUptimeMs());
                PluginToolAdapter adapter = toolAdapters.get(status.getName());
                if (adapter != null) {
                    status.setToolsCount(adapter.getToolCount());
                }
            }
        }
        return statuses.values();
    }

    public Optional<PluginConfig.PluginStatus> getStatus(String name) {
        return Optional.ofNullable(statuses.get(name));
    }

    public Optional<PluginBridge> getBridge(String name) {
        return Optional.ofNullable(bridges.get(name));
    }

    public Optional<PluginToolAdapter> getToolAdapter(String name) {
        return Optional.ofNullable(toolAdapters.get(name));
    }

    public int getPluginCount() {
        return bridges.size();
    }

    // ─── Shutdown ───

    @Override
    public synchronized void close() {
        log.info("Shutting down all plugins ({})", bridges.size());
        List<String> names = new ArrayList<>(bridges.keySet());
        for (String name : names) {
            stopPlugin(name);
        }
        log.info("All plugins stopped");
    }

    // ─── Internals ───

    private String resolveBridgeJsPath() {
        String bridgePath = config.getBridgePath();
        if (bridgePath.startsWith("/") || bridgePath.startsWith("~")) {
            return bridgePath;
        }
        return projectRoot + "/" + bridgePath;
    }

    private Map<String, Object> buildInitParams(PluginConfig.PluginDefinition pluginDef) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("plugin", pluginDef.getName());
        params.put("version", pluginDef.getVersion());
        params.put("projectRoot", projectRoot);

        Map<String, Object> pluginConfig = new LinkedHashMap<>();
        if (config.getPluginConfigs().containsKey(pluginDef.getName())) {
            @SuppressWarnings("unchecked")
            Map<String, Object> globalConfig = (Map<String, Object>) config.getPluginConfigs().get(pluginDef.getName());
            if (globalConfig != null) {
                pluginConfig.putAll(globalConfig);
            }
        }
        if (pluginDef.getConfig() != null) {
            pluginConfig.putAll(pluginDef.getConfig());
        }
        params.put("config", pluginConfig);

        return params;
    }
}
