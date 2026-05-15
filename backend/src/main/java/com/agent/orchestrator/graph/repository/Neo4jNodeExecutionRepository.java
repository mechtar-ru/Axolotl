package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.GraphNodeExecution;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface Neo4jNodeExecutionRepository extends Neo4jRepository<GraphNodeExecution, String> {

    List<GraphNodeExecution> findByRunIdOrderByStartedAtAsc(@Param("runId") String runId);

    List<GraphNodeExecution> findByRunId(@Param("runId") String runId);
}
