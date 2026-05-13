// model/WorkflowSchema.java
package com.agent.orchestrator.model;

import java.util.List;
import java.util.Map;

public class WorkflowSchema {
    private String id;
    private String name;
    private String description;
    private String version = "1.0";
    private String userId;
    private String workspaceId;
    private String defaultModel;
    private List<String> defaultTools;
    private Map<String, Object> defaultToolPermissions;
    private List<Node> nodes;
    private List<Edge> edges;
    private Map<String, Object> metadata;
    private String createdAt;
    private String updatedAt;
    private String appType; // nullable, null => CUSTOM for backward compat
    private String targetPath; // nullable, null means no app mode
    private String targetPathConflictAction; // nullable, "CONTINUE" | "OVERWRITE" | "CHANGE_PATH"
    private Map<String, String> planningModels;
    private String planningOutline;
    private String planningRefinedPlan;
    private String planningContext;

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

    public List<String> getDefaultTools() { return defaultTools; }
    public void setDefaultTools(List<String> defaultTools) { this.defaultTools = defaultTools; }

    public Map<String, Object> getDefaultToolPermissions() { return defaultToolPermissions; }
    public void setDefaultToolPermissions(Map<String, Object> defaultToolPermissions) { this.defaultToolPermissions = defaultToolPermissions; }

    public List<Node> getNodes() { return nodes; }
    public void setNodes(List<Node> nodes) { this.nodes = nodes; }

    public List<Edge> getEdges() { return edges; }
    public void setEdges(List<Edge> edges) { this.edges = edges; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public String getAppType() { return appType; }
    public void setAppType(String appType) { this.appType = appType; }

    public String getTargetPath() { return targetPath; }
    public void setTargetPath(String targetPath) { this.targetPath = targetPath; }

    public String getTargetPathConflictAction() { return targetPathConflictAction; }
    public void setTargetPathConflictAction(String targetPathConflictAction) { this.targetPathConflictAction = targetPathConflictAction; }

    public Map<String, String> getPlanningModels() { return planningModels; }
    public void setPlanningModels(Map<String, String> planningModels) { this.planningModels = planningModels; }

    public String getPlanningOutline() { return planningOutline; }
    public void setPlanningOutline(String planningOutline) { this.planningOutline = planningOutline; }

    public String getPlanningRefinedPlan() { return planningRefinedPlan; }
    public void setPlanningRefinedPlan(String planningRefinedPlan) { this.planningRefinedPlan = planningRefinedPlan; }

    public String getPlanningContext() { return planningContext; }
    public void setPlanningContext(String planningContext) { this.planningContext = planningContext; }
}