package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

@Node("PlanData")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class GraphPlan {

    @Id
    @Property("id")
    private String id;

    @Property("workspaceId")
    private String workspaceId;

    @Property("name")
    private String name;

    @Property("parentId")
    private String parentId;

    @Property("schemaId")
    private String schemaId;

    @Property("level")
    private String level;

    @Property("tasksJson")
    private String tasksJson;

    @Property("createdAt")
    private String createdAt;

    @Property("updatedAt")
    private String updatedAt;










}
