package com.agent.orchestrator.repository;

import com.agent.orchestrator.model.CustomLlmEndpoint;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
public class CustomLlmEndpointRepository {
    private final Map<String, CustomLlmEndpoint> endpoints = new ConcurrentHashMap<>();

    public CustomLlmEndpoint save(CustomLlmEndpoint endpoint) {
        endpoints.put(endpoint.getId(), endpoint);
        return endpoint;
    }

    public Optional<CustomLlmEndpoint> findById(String id) {
        return Optional.ofNullable(endpoints.get(id));
    }

    public List<CustomLlmEndpoint> findAll() {
        return new ArrayList<>(endpoints.values());
    }

    public List<CustomLlmEndpoint> findEnabled() {
        return endpoints.values().stream()
                .filter(CustomLlmEndpoint::isEnabled)
                .sorted(Comparator.comparingInt(CustomLlmEndpoint::getPriority))
                .collect(Collectors.toList());
    }

    public void delete(String id) {
        endpoints.remove(id);
    }
}
