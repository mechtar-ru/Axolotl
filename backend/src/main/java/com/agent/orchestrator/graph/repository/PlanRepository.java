package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.Plan;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import java.util.List;
import java.util.Optional;

public interface PlanRepository extends Neo4jRepository<Plan, String> {
    List<Plan> findByWorkspaceId(String workspaceId);
    Optional<Plan> findById(String id);
    long count();
    void deleteAll();
}