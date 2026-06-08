package com.agent.orchestrator.graph.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.neo4j.core.schema.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

@Node("Plan")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class Plan {
    @Id
    @Property("id")
    private String id;

    @Property("workspaceId")
    private String workspaceId;

    @Property("name")
    private String name;

    @Property("tasksJson")
    private String tasksJson;

    @Property("createdAt")
    private String createdAt;

    @Property("updatedAt")
    private String updatedAt;

    @Property("parentId")
    private String parentId;

    @Property("schemaId")
    private String schemaId;

    @Property("level")
    private String level;


    public Plan(String id, String workspaceId, String name, String tasksJson, String createdAt, String updatedAt, String parentId, String schemaId, String level) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.name = name;
        this.tasksJson = tasksJson;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.parentId = parentId;
        this.schemaId = schemaId;
        this.level = level;
    }

}