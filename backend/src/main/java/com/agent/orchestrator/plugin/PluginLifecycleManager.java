package com.agent.orchestrator.plugin;

import com.agent.orchestrator.service.ToolExecutor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;

/**
 * Spring-managed lifecycle coordinator for the Axolotl plugin system.
 * <p>
 * Initializes the plugin registry on startup, wires it into ToolExecutor,
 * and shuts it down gracefully on exit.
 * <p>
 * Heavy operations (npm auto-update) run asynchronously to avoid blocking
 * Spring context initialization.
 */
@Component
public class PluginLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(PluginLifecycleManager.class);

    private final ToolExecutor toolExecutor;

    @Value("${axolotl.plugins.enabled:false}")
    private boolean pluginEnabled;

    @Value("${axolotl.plugins.bridge-path:plugins/plugin-bridge.js}")
    private String bridgePath;

    @Value("${axolotl.plugins.bun-path:bun}")
    private String bunPath;

    @Value("${axolotl.plugins.startup-timeout-ms:15000}")
    private int startupTimeoutMs;

    @Value("${axolotl.plugins.request-timeout-ms:30000}")
    private int requestTimeoutMs;

    @Value("${axolotl.plugins.max-restart-attempts:3}")
    private int maxRestartAttempts;

    @Value("${axolotl.plugins.restart-backoff-ms:1000}")
    private long restartBackoffMs;

    @Value("${axolotl.plugins.auto-update:true}")
    private boolean autoUpdateEnabled;

    @Value("${axolotl.plugins.auto-update-on-start:true}")
    private boolean autoUpdateOnStart;

    @Value("${user.dir}")
    private String projectRoot;

    /** Plugin definitions from application.yml */
    @Value("${axolotl.plugins.list:}")
    private String pluginListStr;

    private volatile PluginRegistry registry;

    /** Executor for async startup tasks (auto-update) */
    private final ExecutorService startupExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "plugin-startup");
        t.setDaemon(true);
        return t;
    });

    public PluginLifecycleManager(ToolExecutor toolExecutor) {
        this.toolExecutor = toolExecutor;
    }

    @PostConstruct
    public void initialize() {
        if (!pluginEnabled) {
            log.info("Plugin system is disabled (set axolotl.plugins.enabled=true to enable)");
            return;
        }

        // Build config synchronously (fast)
        PluginConfig config = buildConfig();

        // Initialize plugins first (synchronous, bounded by startupTimeoutMs per plugin)
        try {
            registry = new PluginRegistry(config, toolExecutor, projectRoot);
            registry.initialize();
            log.info("Plugin system initialized with {} plugin(s)", registry.getPluginCount());
        } catch (Exception e) {
            log.error("Failed to initialize plugin system: {}", e.getMessage());
        }

        // Auto-update npm packages — run async AFTER plugin init to avoid
        // race on plugins/node_modules/ during loading.
        if (autoUpdateEnabled && autoUpdateOnStart && hasPluginPackageJson()) {
            startupExecutor.submit(() -> {
                try {
                    autoUpdatePackages(config);
                } catch (Exception e) {
                    log.warn("Auto-update failed: {}", e.getMessage());
                }
            });
        }
    }

    @PreDestroy
    public void shutdown() {
        startupExecutor.shutdownNow();
        if (registry != null) {
            registry.close();
        }
    }

    public PluginRegistry getRegistry() {
        return registry;
    }

    public boolean isEnabled() {
        return pluginEnabled && registry != null;
    }

    // ─── Internals ───

    private PluginConfig buildConfig() {
        PluginConfig config = new PluginConfig();
        config.setBridgePath(bridgePath);
        config.setBunPath(bunPath);
        config.setStartupTimeoutMs(startupTimeoutMs);
        config.setRequestTimeoutMs(requestTimeoutMs);
        config.setMaxRestartAttempts(maxRestartAttempts);
        config.setRestartBackoffMs(restartBackoffMs);
        config.setAutoUpdateEnabled(autoUpdateEnabled);
        config.setAutoUpdateOnStart(autoUpdateOnStart);

        // Parse plugin list from comma-separated config value
        // Format: "package1,package2,package3"
        if (pluginListStr != null && !pluginListStr.isBlank()) {
            String[] parts = pluginListStr.split(",");
            for (String part : parts) {
                String pkg = part.trim();
                if (!pkg.isEmpty()) {
                    PluginConfig.PluginDefinition def = new PluginConfig.PluginDefinition();
                    def.setName(pkg);
                    def.setEnabled(true);
                    config.getPlugins().add(def);
                }
            }
        }

        return config;
    }

    private boolean hasPluginPackageJson() {
        return Files.exists(Path.of(projectRoot, "plugins", "package.json"));
    }

    /**
     * Auto-update plugin npm packages at startup.
     * Runs async to not block Spring context initialization.
     */
    private void autoUpdatePackages(PluginConfig config) {
        if (config.getPlugins().isEmpty()) {
            log.debug("No plugins configured, skipping auto-update");
            return;
        }

        try {
            log.info("Auto-updating plugin packages (async)...");
            ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                    "cd " + projectRoot + "/plugins && bun update");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0) {
                log.info("Plugin packages updated successfully");
            } else {
                String output = new String(process.getInputStream().readAllBytes()).trim();
                if (!output.isEmpty()) {
                    log.debug("bun update output: {}", output);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to auto-update plugins: {}", e.getMessage());
        }
    }
}
