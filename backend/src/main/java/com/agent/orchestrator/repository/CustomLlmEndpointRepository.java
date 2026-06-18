package com.agent.orchestrator.repository;

import com.agent.orchestrator.graph.model.GraphCustomLlmEndpoint;
import com.agent.orchestrator.graph.repository.Neo4jCustomLlmEndpointRepository;
import com.agent.orchestrator.model.CustomLlmEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Repository
public class CustomLlmEndpointRepository {

    private static final Logger log = LoggerFactory.getLogger(CustomLlmEndpointRepository.class);

    private final Neo4jCustomLlmEndpointRepository neo4jRepo;
    private final Map<String, CustomLlmEndpoint> cache = new HashMap<>();

    public CustomLlmEndpointRepository(Neo4jCustomLlmEndpointRepository neo4jRepo) {
        this.neo4jRepo = neo4jRepo;
        loadAll();
    }

    private void loadAll() {
        try {
            List<GraphCustomLlmEndpoint> all = neo4jRepo.findAll();
            for (GraphCustomLlmEndpoint g : all) {
                cache.put(g.getId(), toPoco(g));
            }
            log.info("Loaded {} custom LLM endpoints from Neo4j", cache.size());
        } catch (Exception e) {
            log.error("Error loading custom endpoints: {}", e.getMessage(), e);
        }
    }

    private GraphCustomLlmEndpoint toGraph(CustomLlmEndpoint e) {
        GraphCustomLlmEndpoint g = new GraphCustomLlmEndpoint();
        g.setId(e.getId());
        g.setName(e.getName());
        g.setBaseUrl(e.getBaseUrl());
        g.setApiKey(e.getApiKey());
        g.setModelName(e.getModelName());
        g.setAuthType(e.getAuthType());
        g.setEnabled(e.isEnabled());
        g.setCreatedAt(e.getCreatedAt() != null ? e.getCreatedAt().toString() : Instant.now().toString());
        g.setLastUsedAt(e.getLastUsedAt() != null ? e.getLastUsedAt().toString() : null);
        g.setPriority(e.getPriority());
        return g;
    }

    private CustomLlmEndpoint toPoco(GraphCustomLlmEndpoint g) {
        CustomLlmEndpoint e = new CustomLlmEndpoint();
        e.setId(g.getId());
        e.setName(g.getName());
        e.setBaseUrl(g.getBaseUrl());
        e.setApiKey(g.getApiKey());
        e.setModelName(g.getModelName());
        e.setAuthType(g.getAuthType());
        e.setEnabled(g.getEnabled() != null && g.getEnabled());
        if (g.getCreatedAt() != null) e.setCreatedAt(Instant.parse(g.getCreatedAt()));
        if (g.getLastUsedAt() != null) e.setLastUsedAt(Instant.parse(g.getLastUsedAt()));
        e.setPriority(g.getPriority() != null ? g.getPriority() : 100);
        return e;
    }

    public CustomLlmEndpoint save(CustomLlmEndpoint endpoint) {
        try {
            GraphCustomLlmEndpoint g = toGraph(endpoint);
            neo4jRepo.save(g);
            cache.put(endpoint.getId(), endpoint);
        } catch (Exception ex) {
            log.error("Error saving custom endpoint: {}", ex.getMessage(), ex);
            throw new RuntimeException("Error saving custom endpoint: " + ex.getMessage(), ex);
        }
        return endpoint;
    }

    public Optional<CustomLlmEndpoint> findById(String id) {
        return Optional.ofNullable(cache.get(id));
    }

    public List<CustomLlmEndpoint> findAll() {
        return new ArrayList<>(cache.values());
    }

    public List<CustomLlmEndpoint> findEnabled() {
        return cache.values().stream()
                .filter(CustomLlmEndpoint::isEnabled)
                .sorted(Comparator.comparingInt(CustomLlmEndpoint::getPriority))
                .collect(Collectors.toList());
    }

    public Optional<CustomLlmEndpoint> findByName(String name) {
        return cache.values().stream()
                .filter(e -> name != null && name.equalsIgnoreCase(e.getName()))
                .findFirst();
    }

    public boolean existsByName(String name) {
        return cache.values().stream()
                .anyMatch(e -> name != null && name.equalsIgnoreCase(e.getName()));
    }

    public boolean existsByNameExcludingId(String name, String excludeId) {
        return cache.values().stream()
                .anyMatch(e -> name != null && name.equalsIgnoreCase(e.getName()) && !e.getId().equals(excludeId));
    }

    public void delete(String id) {
        try {
            neo4jRepo.deleteById(id);
            cache.remove(id);
        } catch (Exception e) {
            log.error("Error deleting custom endpoint: {}", e.getMessage(), e);
        }
    }
}
