package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

@Node("CustomLlmEndpoint")
@Getter
@Setter
@ToString
@NoArgsConstructor
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











}
