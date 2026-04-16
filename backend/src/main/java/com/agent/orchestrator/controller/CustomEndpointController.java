package com.agent.orchestrator.controller;

import com.agent.orchestrator.model.CustomLlmEndpoint;
import com.agent.orchestrator.repository.CustomLlmEndpointRepository;
import com.agent.orchestrator.llm.CustomLlmProvider;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/settings/endpoints")
public class CustomEndpointController {

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
    public CustomLlmEndpoint create(@RequestBody CustomLlmEndpoint endpoint) {
        return endpointRepository.save(endpoint);
    }

    @PutMapping("/{id}")
    public CustomLlmEndpoint update(@PathVariable String id, @RequestBody CustomLlmEndpoint updated) {
        CustomLlmEndpoint existing = endpointRepository.findById(id).orElse(null);
        if (existing != null) {
            existing.setName(updated.getName());
            existing.setBaseUrl(updated.getBaseUrl());
            existing.setApiKey(updated.getApiKey());
            existing.setModelName(updated.getModelName());
            existing.setAuthType(updated.getAuthType());
            existing.setEnabled(updated.isEnabled());
            existing.setPriority(updated.getPriority());
            return endpointRepository.save(existing);
        }
        return null;
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
