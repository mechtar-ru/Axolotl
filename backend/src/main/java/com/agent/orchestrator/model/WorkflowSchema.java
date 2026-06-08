// model/WorkflowSchema.java
package com.agent.orchestrator.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import lombok.Data;

@Data
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
    private Instant createdAt;
    private Instant updatedAt;
    private String appType; // nullable, null => CUSTOM for backward compat
    private String targetPath; // nullable, null means no app mode
    private String targetPathConflictAction; // nullable, "CONTINUE" | "OVERWRITE" | "CHANGE_PATH"
    private Map<String, String> planningModels;
    private String planningOutline;
    private String planningRefinedPlan;
    private String planningContext;
    private Pipeline pipeline;
    private String projectType; // "FLUTTER" | "PYTHON" | "WEB" | "GO" | "RUST", null => FLUTTER
    private String projectGroup; // nullable, for UI grouping (e.g. "EIOS", "Бережно")
    private boolean autoApproveDrafts;
    private Instant lastRunAt; // ISO-8601 timestamp of last pipeline run

}