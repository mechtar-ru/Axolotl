package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.CodeMethod;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CodeMethodRepository extends Neo4jRepository<CodeMethod, Long> {
    
    Optional<CodeMethod> findBySignature(String signature);
    
    List<CodeMethod> findByNameContaining(String nameFragment);
    
    @Query("""
        MATCH (c:Class)-[:DECLARES]->(m:Method)
        WHERE c.qualifiedName = $classQualifiedName
        RETURN m ORDER BY m.lineNumber
        """)
    List<CodeMethod> findByClass(@Param("classQualifiedName") String classQualifiedName);
    
    @Query("""
        MATCH (m:Method)-[:CALLS]->(called:Method)
        WHERE m.signature = $callerSignature
        RETURN called
        """)
    List<CodeMethod> findCallees(@Param("callerSignature") String callerSignature);
    
    @Query("""
        MATCH (m:Method)<-[:CALLS]-(caller:Method)
        WHERE m.signature = $calleeSignature
        RETURN caller
        """)
    List<CodeMethod> findCallers(@Param("calleeSignature") String calleeSignature);
    
    @Query("""
        MATCH (m:Method)
        WHERE m.name CONTAINS $keyword OR m.signature CONTAINS $keyword
        RETURN m ORDER BY m.name LIMIT $limit
        """)
    List<CodeMethod> search(@Param("keyword") String keyword, @Param("limit") int limit);
}