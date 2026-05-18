package com.agent.orchestrator.graph.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.neo4j.core.schema.*;

@Node("Plan")
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

    public Plan() {}

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

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTasksJson() { return tasksJson; }
    public void setTasksJson(String tasksJson) { this.tasksJson = tasksJson; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }
    public String getSchemaId() { return schemaId; }
    public void setSchemaId(String schemaId) { this.schemaId = schemaId; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
}