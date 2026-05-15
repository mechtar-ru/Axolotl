package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.GraphCustomLlmEndpoint;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface Neo4jCustomLlmEndpointRepository extends Neo4jRepository<GraphCustomLlmEndpoint, String> {
}
