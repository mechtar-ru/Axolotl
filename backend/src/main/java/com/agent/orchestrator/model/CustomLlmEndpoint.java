package com.agent.orchestrator.model;

import java.time.Instant;

public class CustomLlmEndpoint {
    private String id;
    private String name;
    private String baseUrl;
    private String apiKey;
    private String modelName;
    private String authType;
    private boolean enabled;
    private Instant createdAt;
    private Instant lastUsedAt;
    private int priority;

    public CustomLlmEndpoint() {
        this.id = java.util.UUID.randomUUID().toString();
        this.createdAt = Instant.now();
        this.enabled = true;
        this.authType = "bearer";
        this.priority = 100;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
}
