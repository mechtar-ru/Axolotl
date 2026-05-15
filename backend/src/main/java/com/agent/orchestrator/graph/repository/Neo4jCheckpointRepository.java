package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.GraphCheckpoint;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface Neo4jCheckpointRepository extends Neo4jRepository<GraphCheckpoint, String> {

    List<GraphCheckpoint> findByRunIdOrderByCreatedAtAsc(@Param("runId") String runId);

    @Query("""
        MATCH (c:Checkpoint {runId: $runId})
        RETURN c ORDER BY c.createdAt DESC LIMIT 1
        """)
    Optional<GraphCheckpoint> findLatestByRunId(@Param("runId") String runId);
}
