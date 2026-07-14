package com.agent.orchestrator.repository;

import com.agent.orchestrator.model.RefreshToken;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends Neo4jRepository<RefreshToken, String> {

    Optional<RefreshToken> findByToken(@Param("token") String token);

    List<RefreshToken> findByUsernameAndRevokedFalse(@Param("username") String username);

    @Query("MATCH (t:RefreshToken) WHERE t.expiresAt < datetime({epochSeconds: $now}) DETACH DELETE t")
    void deleteExpiredTokens(@Param("now") long now);

    default void revokeAllForUser(String username) {
        findByUsernameAndRevokedFalse(username).forEach(token -> {
            token.setRevoked(true);
            save(token);
        });
    }
}