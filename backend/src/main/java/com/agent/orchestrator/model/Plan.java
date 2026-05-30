package com.agent.orchestrator.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Plan {
    private String id;
    private String workspaceId;
    private String name;
    private String parentId;
    private String schemaId;
    private PlanLevel level = PlanLevel.PROJECT;
    private List<Task> tasks = new ArrayList<>();
    private String sessionGoal;
    private Instant createdAt;
    private Instant updatedAt;

    public Plan() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Plan(String workspaceId, String name) {
        this();
        this.workspaceId = workspaceId;
        this.name = name;
    }

    // Getters and setters
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

    public PlanLevel getLevel() { return level; }
    public void setLevel(PlanLevel level) { this.level = level; }

    public String getSessionGoal() { return sessionGoal; }
    public void setSessionGoal(String sessionGoal) { this.sessionGoal = sessionGoal; }

    public List<Task> getTasks() { return tasks; }
    public void setTasks(List<Task> tasks) { this.tasks = tasks; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
