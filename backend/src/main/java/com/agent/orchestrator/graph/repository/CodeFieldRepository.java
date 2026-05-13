package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.CodeField;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CodeFieldRepository extends Neo4jRepository<CodeField, String> {

    @Query("MATCH (f:Field {signature: $signature}) RETURN f")
    Optional<CodeField> findBySignature(@Param("signature") String signature);

    @Query("MATCH (f:Field {hash: $hash}) RETURN f")
    Optional<CodeField> findByHash(@Param("hash") String hash);

    @Query("MATCH (c:Class {qualifiedName: $className})-[:DECLARES]->(f:Field) RETURN f")
    List<CodeField> findByClassName(@Param("className") String className);
}