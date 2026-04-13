package com.agent.orchestrator.controller;

import com.agent.orchestrator.service.SettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST API для управления настройками провайдеров (API ключи, URL, модели).
 * Без авторизации — настройки доступны всем.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllSettings() {
        List<Map<String, Object>> providers = settingsService.getAllProviderSettings();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("providers", providers);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{provider}")
    public ResponseEntity<Map<String, Object>> getProviderSettings(@PathVariable String provider) {
        Map<String, Object> settings = settingsService.getProviderSettings(provider);
        if (settings == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(settings);
    }

    @PutMapping("/{provider}")
    public ResponseEntity<Map<String, Object>> updateProviderSettings(
            @PathVariable String provider,
            @RequestBody Map<String, String> body) {

        String apiKey = body.get("apiKey");
        String baseUrl = body.get("baseUrl");
        String defaultModel = body.get("defaultModel");

        settingsService.updateProviderSettings(provider, apiKey, baseUrl, defaultModel);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("message", "Настройки провайдера " + provider + " обновлены");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{provider}/health")
    public ResponseEntity<Map<String, Object>> checkProviderHealth(@PathVariable String provider) {
        String apiKey = settingsService.getApiKey(provider);
        String baseUrl = settingsService.getBaseUrl(provider);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("provider", provider);
        response.put("apiKeyConfigured", apiKey != null && !apiKey.isBlank());
        response.put("baseUrl", baseUrl);
        // TODO: actual health check via HTTP call
        response.put("available", apiKey != null && !apiKey.isBlank());
        return ResponseEntity.ok(response);
    }
}
