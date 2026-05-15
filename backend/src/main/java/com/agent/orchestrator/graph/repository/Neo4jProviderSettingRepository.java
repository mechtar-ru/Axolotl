package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.GraphProviderSetting;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface Neo4jProviderSettingRepository extends Neo4jRepository<GraphProviderSetting, String> {

    Optional<GraphProviderSetting> findByProviderName(String providerName);
}
