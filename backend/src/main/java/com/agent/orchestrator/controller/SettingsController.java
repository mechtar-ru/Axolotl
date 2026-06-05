package com.agent.orchestrator.controller;

import com.agent.orchestrator.llm.CustomLlmProvider;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.model.CustomLlmEndpoint;
import com.agent.orchestrator.service.SettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
    private final LlmService llmService;
    private final CustomLlmProvider customLlmProvider;

    public SettingsController(SettingsService settingsService, LlmService llmService,
                              CustomLlmProvider customLlmProvider) {
        this.settingsService = settingsService;
        this.llmService = llmService;
        this.customLlmProvider = customLlmProvider;
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

    @GetMapping("/{provider}/key")
    public ResponseEntity<Map<String, Object>> getProviderApiKey(@PathVariable String provider) {
        String apiKey = settingsService.getApiKey(provider);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("provider", provider);
        response.put("apiKey", apiKey != null ? apiKey : "");
        return ResponseEntity.ok(response);
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
    public ResponseEntity<Map<String, Object>> checkProviderHealth(
            @PathVariable String provider,
            @RequestParam(required = false) String apiKey,
            @RequestParam(required = false) String baseUrl) {
        String storedApiKey = settingsService.getApiKey(provider);
        String storedBaseUrl = settingsService.getBaseUrl(provider);

        // Use passed values if provided (for test-before-save flow)
        String effectiveBaseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : storedBaseUrl;

        // Temp-save the passed key so providers that read from SettingsService pick it up
        boolean tempKeySaved = false;
        if (apiKey != null && !apiKey.isBlank()) {
            settingsService.updateProviderSettings(provider, apiKey, null, null);
            tempKeySaved = true;
        }

        Map<String, Object> health;
        try {
            // Actually ping the provider via LlmService
            health = llmService.checkProviderHealth(provider);
        } finally {
            // Restore the old key after the test
            if (tempKeySaved) {
                settingsService.updateProviderSettings(provider, storedApiKey, null, null);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("provider", provider);
        // Show the effective key status: if user passed a key, consider it configured
        boolean hasKey = (apiKey != null && !apiKey.isBlank()) || (storedApiKey != null && !storedApiKey.isBlank());
        response.put("apiKeyConfigured", hasKey);
        response.put("baseUrl", effectiveBaseUrl);
        response.put("available", health.getOrDefault("available", false));
        response.put("models", health.getOrDefault("models", List.of()));
        if (health.containsKey("error")) {
            response.put("error", health.get("error"));
        }
        return ResponseEntity.ok(response);
    }

    // --- Model toggle endpoints ---

    @GetMapping("/{provider}/models/disabled")
    public ResponseEntity<List<String>> getDisabledModels(@PathVariable String provider) {
        return ResponseEntity.ok(settingsService.getDisabledModels(provider));
    }

    @PutMapping("/{provider}/models/disabled")
    public ResponseEntity<Map<String, Object>> setDisabledModels(
            @PathVariable String provider,
            @RequestBody List<String> disabledModels) {
        settingsService.setDisabledModels(provider, disabledModels);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/default-model")
    public ResponseEntity<Map<String, Object>> getDefaultModel() {
        String model = settingsService.getGlobalDefaultModel();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("defaultModel", model != null ? model : "");
        return ResponseEntity.ok(response);
    }

    @PutMapping("/default-model")
    public ResponseEntity<Map<String, Object>> setDefaultModel(@RequestBody Map<String, String> body) {
        String model = body.get("defaultModel");
        settingsService.setGlobalDefaultModel(model);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("defaultModel", model);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/user/default-model")
    public ResponseEntity<Map<String, Object>> getUserDefaultModel() {
        String userId = getCurrentUserId();
        String model = userId != null ? settingsService.getUserDefaultModel(userId) : null;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("defaultModel", model != null ? model : "");
        return ResponseEntity.ok(response);
    }

    @PutMapping("/user/default-model")
    public ResponseEntity<Map<String, Object>> setUserDefaultModel(@RequestBody Map<String, String> body) {
        String userId = getCurrentUserId();
        String model = body.get("defaultModel");
        if (userId != null) {
            settingsService.setUserDefaultModel(userId, model);
        } else {
            // Fall back to global default when not authenticated so the endpoint
            // never returns 401 (which the frontend treats as logout).
            settingsService.setGlobalDefaultModel(model);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("defaultModel", model);
        return ResponseEntity.ok(response);
    }

    // ──── Provider connection test ────

    @PostMapping("/providers/test")
    public ResponseEntity<Map<String, Object>> testProviderConnection(
            @RequestBody Map<String, Object> body) {
        CustomLlmEndpoint endpoint = new CustomLlmEndpoint();
        endpoint.setBaseUrl((String) body.get("baseUrl"));
        endpoint.setApiKey((String) body.get("apiKey"));
        endpoint.setAuthType((String) body.getOrDefault("authType", "bearer"));
        endpoint.setModelName((String) body.getOrDefault("modelName", "default"));

        boolean success = customLlmProvider.testConnection(endpoint);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", success);
        response.put("message", success ? "Connection successful" : "Connection failed");
        return ResponseEntity.ok(response);
    }

    // ──── Projects folder ────

    @GetMapping("/projects-folder")
    public ResponseEntity<Map<String, Object>> getProjectsFolder() {
        String folder = settingsService.getProjectsFolder();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("projectsFolder", folder != null ? folder : "");
        return ResponseEntity.ok(response);
    }

    @PutMapping("/projects-folder")
    public ResponseEntity<Map<String, Object>> setProjectsFolder(@RequestBody Map<String, String> body) {
        String folder = body.get("projectsFolder");
        settingsService.setProjectsFolder(folder);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("projectsFolder", folder);
        return ResponseEntity.ok(response);
    }

    private String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            return auth.getName();
        }
        return null;
    }
}
