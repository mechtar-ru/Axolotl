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

    @Query("""
        MATCH (r:ExecutionRun {schemaId: $schemaId, status: 'completed'})
        RETURN r ORDER BY r.startedAt DESC LIMIT $limit
        """)
    List<GraphExecutionRun> findCompletedBySchemaId(@Param("schemaId") String schemaId,
                                                     @Param("limit") int limit);

    @Query("""
        MATCH (r:ExecutionRun {id: $runId})
        SET r.status = $status
        SET r.resumeIndex = $resumeIndex
        SET r.updatedAt = toString(datetime())
        """)
    void updateStatusAndResumeIndex(@Param("runId") String runId,
                                     @Param("status") String status,
                                     @Param("resumeIndex") int resumeIndex);

    @Query("""
        MATCH (r:ExecutionRun {id: $runId})
        SET r.resumeIndex = $resumeIndex
        SET r.updatedAt = toString(datetime())
        """)
    void updateResumeIndexOnly(@Param("runId") String runId,
                                @Param("resumeIndex") int resumeIndex);

    @Query("""
        MATCH (r:ExecutionRun {schemaId: $schemaId})
        WHERE r.status = 'paused'
        SET r.status = 'resuming'
        SET r.updatedAt = toString(datetime())
        RETURN r
        ORDER BY r.startedAt DESC LIMIT 1
        """)
    Optional<GraphExecutionRun> claimPausedRun(@Param("schemaId") String schemaId);

    @Query("""
        MATCH (r:ExecutionRun {schemaId: $schemaId})
        WHERE r.status = 'resuming'
        SET r.status = 'paused'
        SET r.updatedAt = toString(datetime())
        """)
    void releasePausedRun(@Param("schemaId") String schemaId);

    @Query("""
        MATCH (r:ExecutionRun {schemaId: $schemaId})
        WHERE r.status = 'resuming'
        SET r.status = 'paused'
        SET r.updatedAt = toString(datetime())
        RETURN count(r)
        """)
    long releaseStaleRuns(@Param("schemaId") String schemaId);

    @Query("""
        MATCH (n:NodeExecution {runId: $runId})
        DETACH DELETE n
        """)
    void deleteNodeExecutionsByRunId(@Param("runId") String runId);

    @Query("""
        MATCH (r:ExecutionRun {id: $runId})
        WHERE r.status = 'paused'
        SET r.status = 'resuming'
        SET r.updatedAt = toString(datetime())
        RETURN r
        """)
    Optional<GraphExecutionRun> claimSpecificRun(@Param("runId") String runId);

    @Query("""
        MATCH (r:ExecutionRun)
        WHERE r.startedAt < $cutoffTimestamp
        WITH r
        MATCH (n:NodeExecution {runId: r.id})
        DETACH DELETE n
        WITH r
        DETACH DELETE r
        RETURN count(r) AS deletedCount
        """)
    long deleteRunsOlderThan(@Param("cutoffTimestamp") String cutoffTimestamp);
}
