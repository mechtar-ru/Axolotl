package com.agent.orchestrator.graph.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.neo4j.core.schema.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

@Node("WorkflowSchema")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class GraphWorkflowSchema {
    @Id
    @Property("id")
    private String id;

    @Property("name")
    private String name;

    @Property("data")
    private String data;

    @Property("userId")
    private String userId;

    @Property("workspaceId")
    private String workspaceId;

    @Property("createdAt")
    private String createdAt;

    @Property("updatedAt")
    private String updatedAt;

    @Property("lastRunAt")
    private String lastRunAt;


}