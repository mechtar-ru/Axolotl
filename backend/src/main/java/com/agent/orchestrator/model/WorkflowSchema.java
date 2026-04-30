// model/WorkflowSchema.java
package com.agent.orchestrator.model;

import java.util.List;
import java.util.Map;

public class WorkflowSchema {
    private String id;
    private String name;
    private String description;
    private String version = "1.0";
    private String userId; // Multi-tenancy: owner
    private String workspaceId; // Workspace grouping
    private String defaultModel;
    private List<String> defaultTools; // Default tools for agent nodes in this schema
    private java.util.Map<String, String> defaultToolPermissions; // Default permissions: toolName -> permission
    private List<Node> nodes;
    private List<Edge> edges;
    private Map<String, Object> metadata;
    private String createdAt;
    private String updatedAt;

    // Геттеры и сеттеры
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }

    public List<Node> getNodes() { return nodes; }
    public void setNodes(List<Node> nodes) { this.nodes = nodes; }

    public List<Edge> getEdges() { return edges; }
    public void setEdges(List<Edge> edges) { this.edges = edges; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public List<String> getDefaultTools() { return defaultTools; }
    public void setDefaultTools(List<String> defaultTools) { this.defaultTools = defaultTools; }

    public Map<String, String> getDefaultToolPermissions() { return defaultToolPermissions; }
    public void setDefaultToolPermissions(Map<String, String> defaultToolPermissions) { this.defaultToolPermissions = defaultToolPermissions; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}