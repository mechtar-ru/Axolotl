package com.agent.orchestrator.controller;

import com.agent.orchestrator.service.PluginService;
import com.agent.orchestrator.service.PluginService.PluginInfo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.Map;

@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    private final PluginService pluginService;

    public PluginController(PluginService pluginService) {
        this.pluginService = pluginService;
    }

    @PostMapping("/install")
    public ResponseEntity<?> install(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        String manager = body.get("manager");

        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Plugin name is required"));
        }
        if (manager == null || manager.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Package manager is required"));
        }

        try {
            PluginInfo info = pluginService.install(name, manager);
            return ResponseEntity.ok(info);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public Collection<PluginInfo> list() {
        return pluginService.list();
    }

    @PostMapping("/{name}/start")
    public ResponseEntity<?> start(@PathVariable String name) {
        try {
            PluginInfo info = pluginService.start(name);
            return ResponseEntity.ok(info);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<?> stop(@PathVariable String name) {
        try {
            PluginInfo info = pluginService.stop(name);
            return ResponseEntity.ok(info);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
