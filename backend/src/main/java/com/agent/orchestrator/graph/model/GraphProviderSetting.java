package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;

@Node("ProviderSetting")
public class GraphProviderSetting {

    @Id
    @Property("providerName")
    private String providerName;

    @Property("apiKey")
    private String apiKey;

    @Property("baseUrl")
    private String baseUrl;

    @Property("defaultModel")
    private String defaultModel;

    @Property("updatedAt")
    private String updatedAt;

    public GraphProviderSetting() {}

    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
