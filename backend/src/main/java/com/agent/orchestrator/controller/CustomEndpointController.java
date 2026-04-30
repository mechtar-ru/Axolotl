package com.agent.orchestrator.controller;

import com.agent.orchestrator.model.CustomLlmEndpoint;
import com.agent.orchestrator.repository.CustomLlmEndpointRepository;
import com.agent.orchestrator.llm.CustomLlmProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/settings/endpoints")
public class CustomEndpointController {

    private static final Set<String> BUILTIN_PROVIDERS = Set.of("ollama", "openai", "anthropic", "deepseek");

    private final CustomLlmEndpointRepository endpointRepository;
    private final CustomLlmProvider customLlmProvider;

    public CustomEndpointController(CustomLlmEndpointRepository endpointRepository, CustomLlmProvider customLlmProvider) {
        this.endpointRepository = endpointRepository;
        this.customLlmProvider = customLlmProvider;
    }

    @GetMapping
    public List<Map<String, Object>> getAll() {
        return endpointRepository.findAll().stream()
                .map(this::toMaskedMap)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable String id) {
        return endpointRepository.findById(id)
                .map(endpoint -> ResponseEntity.ok().body(endpoint))
                .orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Object> toMaskedMap(CustomLlmEndpoint endpoint) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", endpoint.getId());
        map.put("name", endpoint.getName());
        map.put("baseUrl", endpoint.getBaseUrl());
        map.put("modelName", endpoint.getModelName());
        map.put("authType", endpoint.getAuthType());
        map.put("enabled", endpoint.isEnabled());
        map.put("priority", endpoint.getPriority());
        map.put("createdAt", endpoint.getCreatedAt());
        map.put("lastUsedAt", endpoint.getLastUsedAt());
        // Mask API key: show only last 4 chars
        String key = endpoint.getApiKey();
        if (key != null && key.length() > 4) {
            map.put("apiKey", "..." + key.substring(key.length() - 4));
            map.put("hasApiKey", true);
        } else if (key != null) {
            map.put("apiKey", "...");
            map.put("hasApiKey", true);
        } else {
            map.put("apiKey", null);
            map.put("hasApiKey", false);
        }
        return map;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CustomLlmEndpoint endpoint) {
        if (endpoint.getName() == null || endpoint.getName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Name is required"));
        }
        String name = endpoint.getName().trim();
        if (BUILTIN_PROVIDERS.contains(name.toLowerCase())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Name '" + name + "' conflicts with a built-in provider"));
        }
        if (endpointRepository.existsByName(name)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Custom endpoint with name '" + name + "' already exists"));
        }
        endpoint.setName(name);
        return ResponseEntity.ok(endpointRepository.save(endpoint));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody CustomLlmEndpoint updated) {
        CustomLlmEndpoint existing = endpointRepository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (updated.getName() != null && !updated.getName().isBlank()) {
            String name = updated.getName().trim();
            if (BUILTIN_PROVIDERS.contains(name.toLowerCase())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "Name '" + name + "' conflicts with a built-in provider"));
            }
            if (endpointRepository.existsByNameExcludingId(name, id)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "Custom endpoint with name '" + name + "' already exists"));
            }
            existing.setName(name);
        }
        if (updated.getBaseUrl() != null) existing.setBaseUrl(updated.getBaseUrl());
        if (updated.getApiKey() != null) existing.setApiKey(updated.getApiKey());
        if (updated.getModelName() != null) existing.setModelName(updated.getModelName());
        if (updated.getAuthType() != null) existing.setAuthType(updated.getAuthType());
        existing.setEnabled(updated.isEnabled());
        existing.setPriority(updated.getPriority());
        return ResponseEntity.ok(endpointRepository.save(existing));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        endpointRepository.delete(id);
    }

    @PostMapping("/test")
    public Map<String, Object> testConnection(@RequestBody CustomLlmEndpoint endpoint) {
        boolean success = customLlmProvider.testConnection(endpoint);
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", success ? "Connection successful" : "Connection failed");
        return result;
    }
}
