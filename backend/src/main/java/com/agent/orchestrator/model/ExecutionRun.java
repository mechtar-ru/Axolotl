package com.agent.orchestrator.model;

/**
 * Сущность запуска выполнения схемы.
 * Один "клик по Run" = один ExecutionRun.
 * При возобновлении создаётся новый ExecutionRun со ссылкой на родительский.
 */
public class ExecutionRun {
    private String id;
    private String schemaId;
    private String status;       // running | paused | completed | failed | cancelled
    private String mode;         // EXECUTE | ANALYZE | DRY_RUN
    private long totalTokens;
    private double estimatedCost;
    private String error;
    private String resumesFrom;  // nullable FK → parent run id
    private String startedAt;
    private String updatedAt;
    private String completedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

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
}
