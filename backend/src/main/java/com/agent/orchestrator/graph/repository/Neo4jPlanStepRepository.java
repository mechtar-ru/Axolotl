package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.GraphPlanStep;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface Neo4jPlanStepRepository extends Neo4jRepository<GraphPlanStep, String> {

    List<GraphPlanStep> findBySchemaIdOrderByStepIdAsc(@Param("schemaId") String schemaId);

    @Query("MATCH (s:PlanStep {schemaId: $schemaId, status: $status}) RETURN s ORDER BY s.stepId")
    List<GraphPlanStep> findBySchemaIdAndStatus(@Param("schemaId") String schemaId,
                                                 @Param("status") String status);

    @Query("""
        MATCH (s:PlanStep {schemaId: $schemaId, status: 'PENDING'})
        WHERE NOT EXISTS {
            (s)-[:DEPENDS_ON]->(dep:PlanStep)
            WHERE dep.status <> 'DONE'
        }
        RETURN s ORDER BY s.stepId
        """)
    List<GraphPlanStep> findReadySteps(@Param("schemaId") String schemaId);

    @Query("""
        MATCH (s:PlanStep {schemaId: $schemaId})
        OPTIONAL MATCH (s)-[:DEPENDS_ON]->(dep:PlanStep)
        WITH s, dep
        ORDER BY s.stepId, dep.stepId
        RETURN s.id AS stepId,
               s.title AS title,
               s.status AS status,
               collect(CASE WHEN dep IS NOT NULL THEN {id: dep.id, title: dep.title, status: dep.status} END) AS dependencies
        """)
    List<Object[]> getDependencyGraph(@Param("schemaId") String schemaId);

    @Query("MATCH (s:PlanStep {id: $stepId})-[r:DEPENDS_ON]->(dep:PlanStep) RETURN dep")
    List<GraphPlanStep> getDependencies(@Param("stepId") String stepId);

    @Query("MATCH (s:PlanStep {id: $stepId})<-[r:DEPENDS_ON]-(dep:PlanStep) RETURN dep")
    List<GraphPlanStep> getDependents(@Param("stepId") String stepId);

    @Query("MATCH (s:PlanStep {schemaId: $schemaId}) DETACH DELETE s")
    void deleteAllBySchemaId(@Param("schemaId") String schemaId);

    @Query("""
        MATCH (s:PlanStep {schemaId: $schemaId, id: $stepId})
        SET s.status = $status
        SET s.updatedAt = datetime()
        """)
    void updateStatus(@Param("schemaId") String schemaId,
                      @Param("stepId") String stepId,
                      @Param("status") String status);
}
