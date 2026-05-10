package com.agent.orchestrator.graph.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.neo4j.core.schema.*;

@Node("WorkflowSchema")
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

    public GraphWorkflowSchema() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getData() { return data; }
    public void setData(String data) { this.data = data; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}