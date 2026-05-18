package com.agent.orchestrator.graph.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.neo4j.core.schema.*;

@Node("ProviderConfig")
public class ProviderConfig {
    @Id
    @Property("providerName")
    private String providerName;

    @Property("apiKeyHash")
    private String apiKeyHash;

    @Property("baseUrl")
    private String baseUrl;

    @Property("defaultModel")
    private String defaultModel;

    @Property("updatedAt")
    private String updatedAt;

    public ProviderConfig() {}

    public ProviderConfig(String providerName, String apiKeyHash, String baseUrl, String defaultModel, String updatedAt) {
        this.providerName = providerName;
        this.apiKeyHash = apiKeyHash;
        this.baseUrl = baseUrl;
        this.defaultModel = defaultModel;
        this.updatedAt = updatedAt;
    }

    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }
    public String getApiKeyHash() { return apiKeyHash; }
    public void setApiKeyHash(String apiKeyHash) { this.apiKeyHash = apiKeyHash; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}