package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.Decision;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface DecisionRepository extends Neo4jRepository<Decision, String> {
    
    List<Decision> findByTitleContaining(String titleFragment);
    
    List<Decision> findByStatus(String status);
    
    List<Decision> findByPriority(String priority);
    
    @Query("""
        MATCH (d:Decision)
        WHERE d.status = $status AND d.decidedAt >= $since
        RETURN d ORDER BY d.decidedAt DESC
        """)
    List<Decision> findByStatusSince(@Param("status") String status, @Param("since") Instant since);
    
    @Query("""
        MATCH (d:Decision)-[:AFFECTS]->(c:Class)
        WHERE c.qualifiedName = $classQualifiedName
        RETURN d ORDER BY d.decidedAt DESC
        """)
    List<Decision> findByAffectedClass(@Param("classQualifiedName") String classQualifiedName);
    
    @Query("""
        MATCH (d:Decision)
        WHERE d.title CONTAINS $keyword OR d.description CONTAINS $keyword
           OR d.rationale CONTAINS $keyword
        RETURN d ORDER BY d.createdAt DESC LIMIT $limit
        """)
    List<Decision> search(@Param("keyword") String keyword, @Param("limit") int limit);
}