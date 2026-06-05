package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;
import java.util.ArrayList;
import java.util.List;

@Node("PlanStep")
public class GraphPlanStep {

    @Id
    @Property("id")
    private String id;

    @Property("stepId")
    private int stepId;

    @Property("title")
    private String title;

    @Property("description")
    private String description;

    @Property("status")
    private String status = "PENDING";

    @Property("schemaId")
    private String schemaId;

    @Property("planId")
    private String planId;

    @Property("reason")
    private String reason;

    @Property("createdAt")
    private String createdAt;

    @Property("updatedAt")
    private String updatedAt;

    @Relationship(type = "DEPENDS_ON", direction = Relationship.Direction.OUTGOING)
    private List<GraphPlanStep> dependsOn = new ArrayList<>();

    @Relationship(type = "DEPENDS_ON", direction = Relationship.Direction.INCOMING)
    private List<GraphPlanStep> dependedBy = new ArrayList<>();

    public GraphPlanStep() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public int getStepId() { return stepId; }
    public void setStepId(int stepId) { this.stepId = stepId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSchemaId() { return schemaId; }
    public void setSchemaId(String schemaId) { this.schemaId = schemaId; }

    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public List<GraphPlanStep> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<GraphPlanStep> dependsOn) { this.dependsOn = dependsOn; }

    public List<GraphPlanStep> getDependedBy() { return dependedBy; }
    public void setDependedBy(List<GraphPlanStep> dependedBy) { this.dependedBy = dependedBy; }
}
