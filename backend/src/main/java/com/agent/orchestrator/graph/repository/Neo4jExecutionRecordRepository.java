package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.GraphExecutionRecord;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface Neo4jExecutionRecordRepository extends Neo4jRepository<GraphExecutionRecord, String> {

    List<GraphExecutionRecord> findBySchemaIdOrderByStartTimeDesc(@Param("schemaId") String schemaId);

    List<GraphExecutionRecord> findTop50ByOrderByStartTimeDesc();

    @Query("MATCH (r:ExecutionRecord) WHERE r.startTime < $cutoff DETACH DELETE r")
    void deleteRecordsOlderThan(@Param("cutoff") long cutoffTimestamp);
}
