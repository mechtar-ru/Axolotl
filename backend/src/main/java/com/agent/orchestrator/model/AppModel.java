package com.agent.orchestrator.model;

public class AppModel {
    public enum AppType {
        CHAT,
        ANALYZER,
        GENERATOR,
        EMAIL,
        GAME,
        CUSTOM
    }

    private String id;
    private String name;
    private String description;
    private String workspaceId;
    private AppType appType;
    private String targetPath;
    private String targetPathConflictAction;

    public AppModel() {
        this.appType = AppType.CUSTOM;
    }

    // Factory: create from WorkflowSchema
    public static AppModel fromSchema(WorkflowSchema schema) {
        AppModel model = new AppModel();
        model.setId(schema.getId());
        model.setName(schema.getName());
        model.setDescription(schema.getDescription());
        model.setWorkspaceId(schema.getWorkspaceId());
        model.setTargetPath(schema.getTargetPath());
        model.setTargetPathConflictAction(schema.getTargetPathConflictAction());
        String appTypeStr = schema.getAppType();
        if (appTypeStr != null && !appTypeStr.isEmpty()) {
            try {
                model.setAppType(AppType.valueOf(appTypeStr.toUpperCase()));
            } catch (IllegalArgumentException e) {
                model.setAppType(AppType.CUSTOM);
            }
        } else {
            model.setAppType(AppType.CUSTOM);
        }
        return model;
    }

    public WorkflowSchema toSchema() {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId(this.id);
        schema.setName(this.name);
        schema.setDescription(this.description);
        schema.setWorkspaceId(this.workspaceId);
        schema.setAppType(this.appType != null ? this.appType.name() : AppType.CUSTOM.name());
        schema.setTargetPath(this.targetPath);
        schema.setTargetPathConflictAction(this.targetPathConflictAction);
        return schema;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public AppType getAppType() { return appType; }
    public void setAppType(AppType appType) { this.appType = appType; }

    public String getTargetPath() { return targetPath; }
    public void setTargetPath(String targetPath) { this.targetPath = targetPath; }

    public String getTargetPathConflictAction() { return targetPathConflictAction; }
    public void setTargetPathConflictAction(String targetPathConflictAction) { this.targetPathConflictAction = targetPathConflictAction; }
}
