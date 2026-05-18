package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.CodePackage;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CodePackageRepository extends Neo4jRepository<CodePackage, Long> {
    
    Optional<CodePackage> findByPath(String path);
    
    List<CodePackage> findByNameContaining(String nameFragment);
    
    @Query("MATCH (p:Package) WHERE NOT EXISTS((:Package)-[:CONTAINS]->(p)) RETURN p")
    List<CodePackage> findRootPackages();
}