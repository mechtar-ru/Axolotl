package com.agent.orchestrator.graph.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.neo4j.core.schema.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

@Node("LlmEndpoint")
@Getter
@Setter
@ToString
@NoArgsConstructor
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

}