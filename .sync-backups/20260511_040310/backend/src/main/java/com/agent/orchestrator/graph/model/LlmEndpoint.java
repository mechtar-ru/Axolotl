package com.agent.orchestrator.graph.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.neo4j.core.schema.*;

@Node("LlmEndpoint")
public class LlmEndpoint {
    @Id
    @Property("id")
    private String id;

    @Property("name")
    private String name;

    @Property("baseUrl")
    private String baseUrl;

    @Property("apiKeyHash")
    private String apiKeyHash;

    @Property("modelName")
    private String modelName;

    @Property("authType")
    private String authType;

    @Property("enabled")
    private Boolean enabled;

    @Property("createdAt")
    private String createdAt;

    @Property("lastUsedAt")
    private String lastUsedAt;

    @Property("priority")
    private Integer priority;

    public LlmEndpoint() {}

    public LlmEndpoint(String id, String name, String baseUrl, String apiKeyHash, String modelName, String authType, Boolean enabled, String createdAt, String lastUsedAt, Integer priority) {
        this.id = id;
        this.name = name;
        this.baseUrl = baseUrl;
        this.apiKeyHash = apiKeyHash;
        this.modelName = modelName;
        this.authType = authType;
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.lastUsedAt = lastUsedAt;
        this.priority = priority;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKeyHash() { return apiKeyHash; }
    public void setApiKeyHash(String apiKeyHash) { this.apiKeyHash = apiKeyHash; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(String lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
}