package com.agent.orchestrator.model;

import java.time.Instant;

import lombok.Data;

@Data
public class Skill {
    private String id;
    private String name;
    private String description;
    private String promptTemplate;
    private String triggerPattern;
    private int usageCount;
    private double successRate;
    private Instant createdAt;
    private Instant lastUsedAt;
    private String category;
    private boolean enabled;

    public Skill() {
        this.id = java.util.UUID.randomUUID().toString();
        this.createdAt = Instant.now();
        this.enabled = true;
        this.usageCount = 0;
        this.successRate = 0.0;
    }

    public Skill(String name, String description, String promptTemplate, String triggerPattern) {
        this();
        this.name = name;
        this.description = description;
        this.promptTemplate = promptTemplate;
        this.triggerPattern = triggerPattern;
    }

    public void incrementUsage() { this.usageCount++; }
}
