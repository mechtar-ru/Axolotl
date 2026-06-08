package com.agent.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.Data;

@Data
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


    @Data
    public static class GeneratedFile {
        private String path;
        private String description;

        public GeneratedFile() {}

        public GeneratedFile(String path, String description) {
            this.path = path;
            this.description = description;
        }

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
