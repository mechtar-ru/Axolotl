package com.agent.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.Data;

@Data
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


    public void touch() {
        this.updatedAt = Instant.now();
    }
}
