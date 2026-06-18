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

    @Query("MATCH (r:ExecutionRun {schemaId: $schemaId}) RETURN r ORDER BY r.startedAt DESC LIMIT 100")
    List<GraphExecutionRun> findBySchemaIdOrderByStartedAtDesc(@Param("schemaId") String schemaId);

    @Query("""
        MATCH (r:ExecutionRun {status: $status})
        RETURN r ORDER BY r.startedAt DESC LIMIT 1
        """)
    List<GraphExecutionRun> findByStatus(@Param("status") String status);

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
        SET r.status = $status,
            r.resumeIndex = $resumeIndex,
            r.updatedAt = datetime(),
            r.version = r.version + 1
        """)
    void updateStatusAndResumeIndex(@Param("runId") String runId,
                                     @Param("status") String status,
                                     @Param("resumeIndex") int resumeIndex);

    @Query("""
        MATCH (r:ExecutionRun {id: $runId})
        SET r.resumeIndex = $resumeIndex,
            r.updatedAt = datetime(),
            r.version = r.version + 1
        """)
    void updateResumeIndexOnly(@Param("runId") String runId,
                                @Param("resumeIndex") int resumeIndex);

    @Query("""
        MATCH (r:ExecutionRun {schemaId: $schemaId, status: 'paused'})
        SET r.status = 'resuming',
            r.updatedAt = datetime(),
            r.version = r.version + 1
        RETURN r
        ORDER BY r.startedAt DESC LIMIT 1
        """)
    Optional<GraphExecutionRun> claimPausedRun(@Param("schemaId") String schemaId);

    @Query("""
        MATCH (r:ExecutionRun {schemaId: $schemaId, status: 'resuming'})
        SET r.status = 'paused',
            r.updatedAt = datetime(),
            r.version = r.version + 1
        """)
    void releasePausedRun(@Param("schemaId") String schemaId);

    @Query("""
        MATCH (r:ExecutionRun {schemaId: $schemaId, status: 'resuming'})
        SET r.status = 'paused',
            r.updatedAt = datetime(),
            r.version = r.version + 1
        RETURN count(r)
        """)
    long releaseStaleRuns(@Param("schemaId") String schemaId);

    @Query("""
        MATCH (n:NodeExecution {runId: $runId})
        DETACH DELETE n
        """)
    void deleteNodeExecutionsByRunId(@Param("runId") String runId);

    @Query("""
        MATCH (r:ExecutionRun {id: $runId, status: 'paused'})
        SET r.status = 'resuming',
            r.updatedAt = datetime(),
            r.version = r.version + 1
        RETURN r
        """)
    Optional<GraphExecutionRun> claimSpecificRun(@Param("runId") String runId);

    @Query("""
        MATCH (r:ExecutionRun)
        WHERE r.startedAt < datetime({epochSeconds: toInteger($cutoffSeconds)})
        WITH r
        MATCH (n:NodeExecution {runId: r.id})
        DETACH DELETE n
        WITH r
        DETACH DELETE r
        RETURN count(r) AS deletedCount
        """)
    long deleteRunsOlderThan(@Param("cutoffSeconds") String cutoffSeconds);

    @Query("""
        MATCH (r:ExecutionRun)
        WHERE r.status IN ['running', 'paused']
        RETURN r LIMIT 50
        """)
    List<GraphExecutionRun> findStaleRuns();

    @Query("""
        MATCH (r:ExecutionRun {id: $runId})
        SET r.status = $status,
            r.error = $error,
            r.updatedAt = datetime(),
            r.version = r.version + 1
        """)
    void forceUpdateRunStatus(@Param("runId") String runId,
                               @Param("status") String status,
                               @Param("error") String error);

    @Query("""
        MATCH (n:NodeExecution {runId: $runId, status: 'running'})
        SET n.status = 'failed'
        SET n.error = $error
        """)
    void failRunningNodeExecutions(@Param("runId") String runId,
                                   @Param("error") String error);
}
