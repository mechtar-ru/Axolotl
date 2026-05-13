package com.agent.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Task {
    private String id;
    private String title;
    private String description;
    private TaskStatus status = TaskStatus.TODO;
    private Priority priority = Priority.MEDIUM;
    private List<String> dependencies = new ArrayList<>();
    private String nodeId;
    private String schemaId;
    private String reason;
    private int order;
    private List<String> acceptanceCriteria = new ArrayList<>();
    private List<Boolean> acceptanceCriteriaMet = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;
    private List<GeneratedFile> generatedFiles = new ArrayList<>();

    public Task() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Task(String title) {
        this();
        this.title = title;
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }

    public Priority getPriority() { return priority; }
    public void setPriority(Priority priority) { this.priority = priority; }

    public List<String> getDependencies() { return dependencies; }
    public void setDependencies(List<String> dependencies) { this.dependencies = dependencies; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getSchemaId() { return schemaId; }
    public void setSchemaId(String schemaId) { this.schemaId = schemaId; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public int getOrder() { return order; }
    public void setOrder(int order) { this.order = order; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public List<String> getAcceptanceCriteria() { return acceptanceCriteria; }
    public void setAcceptanceCriteria(List<String> acceptanceCriteria) { this.acceptanceCriteria = acceptanceCriteria; }

    public List<Boolean> getAcceptanceCriteriaMet() { return acceptanceCriteriaMet; }
    public void setAcceptanceCriteriaMet(List<Boolean> acceptanceCriteriaMet) { this.acceptanceCriteriaMet = acceptanceCriteriaMet; }

    public List<GeneratedFile> getGeneratedFiles() { return generatedFiles; }
    public void setGeneratedFiles(List<GeneratedFile> generatedFiles) { this.generatedFiles = generatedFiles; }

    public static class GeneratedFile {
        private String path;
        private String description;

        public GeneratedFile() {}

        public GeneratedFile(String path, String description) {
            this.path = path;
            this.description = description;
        }

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    // Acceptance Criteria helpers
    @JsonIgnore
    public int getCriteriaMetCount() {
        if (acceptanceCriteriaMet == null) return 0;
        return (int) acceptanceCriteriaMet.stream().filter(Boolean::booleanValue).count();
    }

    public boolean allCriteriaMet() {
        if (acceptanceCriteria.isEmpty()) return true;
        return acceptanceCriteriaMet != null && acceptanceCriteriaMet.size() == acceptanceCriteria.size()
                && acceptanceCriteriaMet.stream().allMatch(Boolean::booleanValue);
    }
}
