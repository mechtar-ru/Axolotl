package com.agent.orchestrator.controller;

import com.agent.orchestrator.service.FeatureFlagService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/features")
public class FeatureFlagController {

    private final FeatureFlagService featureFlagService;

    public FeatureFlagController(FeatureFlagService featureFlagService) {
        this.featureFlagService = featureFlagService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Boolean>> getAllFlags() {
        return ResponseEntity.ok(featureFlagService.getAllFlags());
    }

    @GetMapping("/{flagName}")
    public ResponseEntity<Map<String, Object>> getFlag(@PathVariable String flagName) {
        boolean enabled = featureFlagService.isEnabled(flagName);
        return ResponseEntity.ok(Map.of("name", flagName, "enabled", enabled));
    }

    @PutMapping("/{flagName}")
    public ResponseEntity<Void> setFlag(@PathVariable String flagName, @RequestBody Map<String, Boolean> body) {
        if (body.containsKey("enabled")) {
            featureFlagService.setOverride(flagName, body.get("enabled"));
        }
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{flagName}")
    public ResponseEntity<Void> clearOverride(@PathVariable String flagName) {
        featureFlagService.clearOverride(flagName);
        return ResponseEntity.ok().build();
    }
}
