package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;

@Node("CustomLlmEndpoint")
public class GraphCustomLlmEndpoint {

    @Id
    @Property("id")
    private String id;

    @Property("name")
    private String name;

    @Property("baseUrl")
    private String baseUrl;

    @Property("apiKey")
    private String apiKey;

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

    public GraphCustomLlmEndpoint() {}

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

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(String lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
}
