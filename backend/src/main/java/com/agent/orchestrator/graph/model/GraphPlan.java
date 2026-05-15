package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;

@Node("PlanData")
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

    public GraphPlan() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }

    public String getSchemaId() { return schemaId; }
    public void setSchemaId(String schemaId) { this.schemaId = schemaId; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public String getTasksJson() { return tasksJson; }
    public void setTasksJson(String tasksJson) { this.tasksJson = tasksJson; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
