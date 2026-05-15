package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.GraphExecutionRun;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface Neo4jExecutionRunRepository extends Neo4jRepository<GraphExecutionRun, String> {

    List<GraphExecutionRun> findBySchemaIdOrderByStartedAtDesc(@Param("schemaId") String schemaId);

    @Query("""
        MATCH (r:ExecutionRun {schemaId: $schemaId, status: $status})
        RETURN r ORDER BY r.startedAt DESC LIMIT 1
        """)
    Optional<GraphExecutionRun> findLatestBySchemaIdAndStatus(
        @Param("schemaId") String schemaId,
        @Param("status") String status
    );

    @Query("""
        MATCH (r:ExecutionRun {schemaId: $schemaId})
        WHERE r.status = 'running' OR r.status = 'paused'
        RETURN count(r) > 0
        """)
    boolean hasActiveRun(@Param("schemaId") String schemaId);

    @Query("""
        MATCH (r:ExecutionRun {schemaId: $schemaId, status: 'running'})
        RETURN r ORDER BY r.startedAt DESC LIMIT 1
        """)
    Optional<GraphExecutionRun> findLatestRunningBySchemaId(@Param("schemaId") String schemaId);
}
