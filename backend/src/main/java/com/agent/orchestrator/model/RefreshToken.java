package com.agent.orchestrator.model;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

import java.time.Instant;

@Data
@Node("RefreshToken")
public class RefreshToken {

    @Id
    private String id;

    @Property("username")
    private String username;

    @Property("token")
    private String token;

    @Property("expiresAt")
    private Instant expiresAt;

    @Property("revoked")
    private boolean revoked;

    @Property("createdAt")
    private Instant createdAt;

    public RefreshToken() {}

    public RefreshToken(String username, String token, Instant expiresAt) {
        this.id = java.util.UUID.randomUUID().toString();
        this.username = username;
        this.token = token;
        this.expiresAt = expiresAt;
        this.revoked = false;
        this.createdAt = Instant.now();
    }

    public void setRevoked(boolean revoked) {
        this.revoked = revoked;
    }
}