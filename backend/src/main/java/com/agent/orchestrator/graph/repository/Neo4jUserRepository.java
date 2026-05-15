package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.GraphUser;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface Neo4jUserRepository extends Neo4jRepository<GraphUser, String> {

    @Query("MATCH (u:User {username: $username}) RETURN u")
    Optional<GraphUser> findByUsername(@Param("username") String username);
}
