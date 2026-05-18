package com.agent.orchestrator.model;

import java.time.Instant;

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

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getPromptTemplate() { return promptTemplate; }
    public void setPromptTemplate(String promptTemplate) { this.promptTemplate = promptTemplate; }

    public String getTriggerPattern() { return triggerPattern; }
    public void setTriggerPattern(String triggerPattern) { this.triggerPattern = triggerPattern; }

    public int getUsageCount() { return usageCount; }
    public void setUsageCount(int usageCount) { this.usageCount = usageCount; }
    public void incrementUsage() { this.usageCount++; }

    public double getSuccessRate() { return successRate; }
    public void setSuccessRate(double successRate) { this.successRate = successRate; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
