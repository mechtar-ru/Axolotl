package com.agent.orchestrator.graph.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.neo4j.core.schema.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

@Node("ProviderConfig")
@Getter
@Setter
@ToString
@NoArgsConstructor
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


    public ProviderConfig(String providerName, String apiKeyHash, String baseUrl, String defaultModel, String updatedAt) {
        this.providerName = providerName;
        this.apiKeyHash = apiKeyHash;
        this.baseUrl = baseUrl;
        this.defaultModel = defaultModel;
        this.updatedAt = updatedAt;
    }

}