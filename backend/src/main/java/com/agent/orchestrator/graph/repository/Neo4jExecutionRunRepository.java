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
        MATCH (r:ExecutionRun {id: $runId})
        SET r.stageStatus = coalesce(r.stageStatus, {}) + $update
        SET r.updatedAt = toString(datetime())
        """)
    void updateStageStatusAtomic(@Param("runId") String runId, @Param("update") java.util.Map<String, String> update);

    @Query("""
        MATCH (r:ExecutionRun {id: $runId})
        SET r.stageOutputs = coalesce(r.stageOutputs, {}) + $update
        SET r.updatedAt = toString(datetime())
        """)
    void updateStageOutputAtomic(@Param("runId") String runId, @Param("update") java.util.Map<String, String> update);

    @Query("""
        MATCH (r:ExecutionRun {id: $runId})
        SET r.status = $status
        SET r.resumeIndex = $resumeIndex
        SET r.updatedAt = toString(datetime())
        """)
    void updateStatusAndResumeIndex(@Param("runId") String runId,
                                     @Param("status") String status,
                                     @Param("resumeIndex") int resumeIndex);

    /**
     * Updates ONLY the resumeIndex without touching the status field.
     * Used by persistResumeState() to avoid overwriting the 'paused' status.
     */
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
        """)
    Optional<GraphExecutionRun> claimPausedRun(@Param("schemaId") String schemaId);
}
