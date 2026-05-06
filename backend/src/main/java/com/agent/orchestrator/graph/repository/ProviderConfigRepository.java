package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.ProviderConfig;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import java.util.Optional;

public interface ProviderConfigRepository extends Neo4jRepository<ProviderConfig, String> {
    Optional<ProviderConfig> findByProviderName(String providerName);
    long count();
    void deleteAll();
}