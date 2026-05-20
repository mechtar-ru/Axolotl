package com.agent.orchestrator.graph.model;

import org.springframework.data.annotation.Version;
import org.springframework.data.neo4j.core.schema.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Node("ExecutionRun")
public class GraphExecutionRun {

    @Id
    @Property("id")
    private String id;

    @Version
    private Long version;

    @Property("schemaId")
    private String schemaId;

    @Property("status")
    private String status;

    @Property("mode")
    private String mode;

    @Property("totalTokens")
    private long totalTokens;

    @Property("estimatedCost")
    private double estimatedCost;

    @Property("error")
    private String error;

    @Property("resumesFrom")
    private String resumesFrom;

    @Property("startedAt")
    private String startedAt;

    @Property("updatedAt")
    private String updatedAt;

    @Property("completedAt")
    private String completedAt;

    @Property("stageStatus")
    private Map<String, String> stageStatus = new HashMap<>();

    @Property("stageOutputs")
    private Map<String, String> stageOutputs = new HashMap<>();

    public GraphExecutionRun() {}

    public GraphExecutionRun(String id, String schemaId, String status, String mode) {
        this.id = id;
        this.schemaId = schemaId;
        this.status = status;
        this.mode = mode;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public String getSchemaId() { return schemaId; }
    public void setSchemaId(String schemaId) { this.schemaId = schemaId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public long getTotalTokens() { return totalTokens; }
    public void setTotalTokens(long totalTokens) { this.totalTokens = totalTokens; }

    public double getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(double estimatedCost) { this.estimatedCost = estimatedCost; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getResumesFrom() { return resumesFrom; }
    public void setResumesFrom(String resumesFrom) { this.resumesFrom = resumesFrom; }

    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public String getCompletedAt() { return completedAt; }
    public void setCompletedAt(String completedAt) { this.completedAt = completedAt; }

    public Map<String, String> getStageStatus() { return stageStatus; }
    public void setStageStatus(Map<String, String> stageStatus) { this.stageStatus = stageStatus; }

    public Map<String, String> getStageOutputs() { return stageOutputs; }
    public void setStageOutputs(Map<String, String> stageOutputs) { this.stageOutputs = stageOutputs; }
}
