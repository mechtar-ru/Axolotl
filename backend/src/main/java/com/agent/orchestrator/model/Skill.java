package com.agent.orchestrator.model;

import java.time.Instant;

public class Skill {
    private String id;
    private String name;
    private String description;
    private String version; // agentskills.io: version field
    private String promptTemplate;
    private String triggerPattern;
    private int usageCount;
    private double successRate;
    private Instant createdAt;
    private Instant lastUsedAt;
    private String category;
    private boolean enabled;
    private java.util.List<String> authors; // agentskills.io: authors field
    private java.util.List<String> tags; // agentskills.io: tags field
    private java.util.List<String> platforms; // agentskills.io: platforms field
    private String repository; // agentskills.io: repository URL
    private String docs; // agentskills.io: docs URL
    private String source; // GitHub source (e.g., "user/repo")
    private String sourceType; // "github", "url", etc.
    private String computedHash; // Hash of the skill content

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

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public java.util.List<String> getAuthors() { return authors; }
    public void setAuthors(java.util.List<String> authors) { this.authors = authors; }

    public java.util.List<String> getTags() { return tags; }
    public void setTags(java.util.List<String> tags) { this.tags = tags; }

    public java.util.List<String> getPlatforms() { return platforms; }
    public void setPlatforms(java.util.List<String> platforms) { this.platforms = platforms; }

    public String getRepository() { return repository; }
    public void setRepository(String repository) { this.repository = repository; }

    public String getDocs() { return docs; }
    public void setDocs(String docs) { this.docs = docs; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public String getComputedHash() { return computedHash; }
    public void setComputedHash(String computedHash) { this.computedHash = computedHash; }
}
