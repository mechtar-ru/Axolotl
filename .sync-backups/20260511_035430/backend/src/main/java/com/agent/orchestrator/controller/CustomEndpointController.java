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
    public List<CustomLlmEndpoint> getAll() {
        return endpointRepository.findAll();
    }

    @GetMapping("/enabled")
    public List<CustomLlmEndpoint> getEnabled() {
        return endpointRepository.findEnabled();
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
