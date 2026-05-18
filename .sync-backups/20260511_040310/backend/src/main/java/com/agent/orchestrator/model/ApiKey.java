package com.agent.orchestrator.model;

import java.time.Instant;

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

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String keyHash) { this.keyHash = keyHash; }

    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String[] getScopes() { return scopes; }
    public void setScopes(String[] scopes) { this.scopes = scopes; }

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
}
