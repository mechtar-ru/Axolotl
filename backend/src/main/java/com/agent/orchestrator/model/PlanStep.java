package com.agent.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PlanStep {
    private String id;
    private int stepId;
    private String title;
    private String description;
    private PlanStepStatus status = PlanStepStatus.PENDING;
    private String schemaId;
    private String planId;
    private List<String> dependsOn = new ArrayList<>();
    private String reason;
    private Instant createdAt;
    private Instant updatedAt;

    public PlanStep() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public PlanStep(String title) {
        this();
        this.title = title;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public int getStepId() { return stepId; }
    public void setStepId(int stepId) { this.stepId = stepId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public PlanStepStatus getStatus() { return status; }
    public void setStatus(PlanStepStatus status) { this.status = status; }

    public String getSchemaId() { return schemaId; }
    public void setSchemaId(String schemaId) { this.schemaId = schemaId; }

    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }

    public List<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<String> dependsOn) { this.dependsOn = dependsOn; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
