package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.WorkflowSchema;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import java.util.List;
import java.util.Optional;

public interface WorkflowSchemaRepository extends Neo4jRepository<WorkflowSchema, String> {
    Optional<WorkflowSchema> findById(String id);
    List<WorkflowSchema> findByUserId(String userId);
    List<WorkflowSchema> findByWorkspaceId(String workspaceId);
    long count();
    void deleteAll();
}