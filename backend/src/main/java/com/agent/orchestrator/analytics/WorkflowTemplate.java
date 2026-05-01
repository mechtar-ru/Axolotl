package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.WorkflowSchema;
import java.util.*;

/**
 * Represents a workflow template.
 */


public class WorkflowTemplate {
    private String id;
    private String name;
    private String description;
    private List<String> tags;
    private String category;
    private WorkflowSchema workflow;
    private Date createdAt;
    private Date updatedAt;

    public WorkflowTemplate() {
        this.id = UUID.randomUUID().toString();
        this.tags = new ArrayList<>();
        this.createdAt = new Date();
        this.updatedAt = new Date();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public WorkflowSchema getWorkflow() { return workflow; }
    public void setWorkflow(WorkflowSchema workflow) { this.workflow = workflow; }

    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }

    public Date getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
