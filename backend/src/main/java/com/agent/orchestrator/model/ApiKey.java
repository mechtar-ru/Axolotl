package com.agent.orchestrator.model;

import lombok.Data;
import java.time.Instant;

@Data
public class ApiKey {
    private String id;
    private String name;
    private String keyHash;
    private String keyPrefix;
    private String userId;
    private Instant createdAt;
    private Instant expiresAt;
    private boolean enabled;
    private String[] scopes;
    private String webhookUrl;

    public ApiKey() {}

    public ApiKey(String name, String keyHash, String keyPrefix, String userId) {
        this.id = java.util.UUID.randomUUID().toString();
        this.name = name;
        this.keyHash = keyHash;
        this.keyPrefix = keyPrefix;
        this.userId = userId;
        this.createdAt = Instant.now();
        this.enabled = true;
        this.scopes = new String[] { "workflows:read", "workflows:execute" };
    }
}
