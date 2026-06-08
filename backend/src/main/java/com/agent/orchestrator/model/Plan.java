package com.agent.orchestrator.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.Data;

@Data
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


    public void touch() {
        this.updatedAt = Instant.now();
    }
}
