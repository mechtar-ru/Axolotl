package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.GraphPlan;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface Neo4jPlanDataRepository extends Neo4jRepository<GraphPlan, String> {

    Optional<GraphPlan> findByWorkspaceId(@Param("workspaceId") String workspaceId);

    List<GraphPlan> findAllByWorkspaceId(@Param("workspaceId") String workspaceId);

    @Query("MATCH (p:PlanData) RETURN DISTINCT p.workspaceId AS workspaceId ORDER BY p.workspaceId")
    List<String> findAllWorkspaceIds();

    List<GraphPlan> findByParentIdOrderByCreatedAt(@Param("parentId") String parentId);

    @Query("MATCH (p:PlanData {schemaId: $schemaId}) RETURN p")
    Optional<GraphPlan> findBySchemaId(@Param("schemaId") String schemaId);
}
