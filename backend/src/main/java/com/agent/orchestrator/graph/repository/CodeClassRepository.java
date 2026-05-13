package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.CodeClass;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CodeClassRepository extends Neo4jRepository<CodeClass, String> {
    
    Optional<CodeClass> findByQualifiedName(String qualifiedName);

    Optional<CodeClass> findByHash(String hash);
    
    List<CodeClass> findByNameContaining(String nameFragment);
    
    List<CodeClass> findByPackageName(String packageName);
    
    @Query("""
        MATCH (c:Class)-[r:DEPENDS_ON]->(dep:Class)
        WHERE c.qualifiedName = $qualifiedName
        RETURN dep ORDER BY dep.name
        """)
    List<CodeClass> findDependencies(@Param("qualifiedName") String qualifiedName);
    
    @Query("""
        MATCH (d:Decision)-[:AFFECTS]->(c:Class)
        WHERE d.status = 'accepted'
        RETURN c ORDER BY d.decidedAt DESC LIMIT $limit
        """)
    List<CodeClass> findRecentlyImpactedClasses(@Param("limit") int limit);
    
    @Query("""
        MATCH (c:Class)
        WHERE c.name CONTAINS $keyword OR c.qualifiedName CONTAINS $keyword
        RETURN c ORDER BY c.name LIMIT $limit
        """)
    List<CodeClass> search(@Param("keyword") String keyword, @Param("limit") int limit);

    @Query("MATCH (c:Class) WHERE c.imports CONTAINS $importPattern RETURN c LIMIT 20")
    List<CodeClass> findByImportsContaining(@Param("importPattern") String importPattern);

    @Query("MATCH (c:Class) WHERE c.qualifiedName CONTAINS $qualifiedName RETURN c")
    List<CodeClass> findByQualifiedNameContaining(@Param("qualifiedName") String qualifiedName);

    List<CodeClass> findByNameContainingOrQualifiedNameContaining(String name, String qualifiedName);
}