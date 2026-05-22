package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;

import java.util.List;

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

    @Property("disabledModels")
    private List<String> disabledModels;

    @Property("models")
    private List<String> models;

    @Property("projectsFolder")
    private String projectsFolder;

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

    public List<String> getDisabledModels() { return disabledModels; }
    public void setDisabledModels(List<String> disabledModels) { this.disabledModels = disabledModels; }

    public List<String> getModels() { return models; }
    public void setModels(List<String> models) { this.models = models; }

    public String getProjectsFolder() { return projectsFolder; }
    public void setProjectsFolder(String projectsFolder) { this.projectsFolder = projectsFolder; }
}
