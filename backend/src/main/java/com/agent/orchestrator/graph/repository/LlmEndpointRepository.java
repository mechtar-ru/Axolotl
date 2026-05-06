package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.LlmEndpoint;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import java.util.List;
import java.util.Optional;

public interface LlmEndpointRepository extends Neo4jRepository<LlmEndpoint, String> {
    List<LlmEndpoint> findByEnabledTrue();
    Optional<LlmEndpoint> findById(String id);
    long count();
    void deleteAll();
}