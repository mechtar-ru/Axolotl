package com.agent.orchestrator.plugin;

import com.agent.orchestrator.plugin.PluginConfig.PluginStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST API for managing OpenCode plugins in Axolotl.
 *
 * Endpoints:
 *   GET    /api/open-plugins              — list all plugins with status
 *   GET    /api/open-plugins/{name}       — get plugin status
 *   POST   /api/open-plugins/install      — install a new plugin (npm package)
 *   POST   /api/open-plugins/{name}/restart — restart a plugin
 *   POST   /api/open-plugins/{name}/stop  — stop a plugin
 *   POST   /api/open-plugins/{name}/start — start a stopped plugin
 *   GET    /api/open-plugins/settings     — get plugin system settings
 */
@RestController
@RequestMapping("/api/open-plugins")
public class PluginManagerController {

    private final PluginLifecycleManager lifecycleManager;

    public PluginManagerController(PluginLifecycleManager lifecycleManager) {
        this.lifecycleManager = lifecycleManager;
    }

    /** List all plugins with their status */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listPlugins() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("enabled", lifecycleManager.isEnabled());

        PluginRegistry registry = lifecycleManager.getRegistry();
        if (registry != null) {
            response.put("running", registry.getPluginCount());
            response.put("plugins", registry.getAllStatuses());
        } else {
            response.put("running", 0);
            response.put("plugins", List.of());
        }

        return ResponseEntity.ok(response);
    }

    /** Get status of a specific plugin */
    @GetMapping("/{name}")
    public ResponseEntity<?> getPlugin(@PathVariable String name) {
        PluginRegistry registry = lifecycleManager.getRegistry();
        if (registry == null) {
            return ResponseEntity.ok(Map.of("error", "Plugin system not initialized"));
        }

        return registry.getStatus(name)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Install a new plugin from npm */
    @PostMapping("/install")
    public ResponseEntity<Map<String, Object>> installPlugin(@RequestBody Map<String, Object> body) {
        PluginRegistry registry = lifecycleManager.getRegistry();
        if (registry == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Plugin system not enabled"));
        }

        String npmPackage = (String) body.get("package");
        if (npmPackage == null || npmPackage.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'package' field"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> pluginConfig = (Map<String, Object>) body.get("config");

        try {
            PluginStatus status = registry.installPlugin(npmPackage, pluginConfig);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "name", status.getName(),
                    "status", status.getStatus(),
                    "tools", status.getToolsCount()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /** Restart a plugin */
    @PostMapping("/{name}/restart")
    public ResponseEntity<Map<String, Object>> restartPlugin(@PathVariable String name) {
        PluginRegistry registry = lifecycleManager.getRegistry();
        if (registry == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Plugin system not enabled"));
        }

        try {
            registry.restartPlugin(name);
            return ResponseEntity.ok(Map.of("success", true, "name", name));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /** Stop a plugin */
    @PostMapping("/{name}/stop")
    public ResponseEntity<Map<String, Object>> stopPlugin(@PathVariable String name) {
        PluginRegistry registry = lifecycleManager.getRegistry();
        if (registry == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Plugin system not enabled"));
        }

        registry.stopPlugin(name);
        return ResponseEntity.ok(Map.of("success", true, "name", name));
    }

    /** Start a stopped plugin */
    @PostMapping("/{name}/start")
    public ResponseEntity<Map<String, Object>> startPlugin(@PathVariable String name) {
        PluginRegistry registry = lifecycleManager.getRegistry();
        if (registry == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Plugin system not enabled"));
        }

        // Check if plugin config exists before attempting restart
        if (registry.getStatus(name).isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        try {
            registry.restartPlugin(name);
            return ResponseEntity.ok(Map.of("success", true, "name", name));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /** Get plugin system settings */
    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> getSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("enabled", lifecycleManager.isEnabled());
        settings.put("autoUpdate", true);
        return ResponseEntity.ok(settings);
    }
}
