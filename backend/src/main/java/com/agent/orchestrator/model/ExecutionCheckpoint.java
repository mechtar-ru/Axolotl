package com.agent.orchestrator.model;

/**
 * Чекпоинт выполнения — сохраняется после каждой топологической волны.
 * Позволяет восстановить состояние при возобновлении после краша.
 */
public class ExecutionCheckpoint {
    private String id;
    private String runId;              // FK → execution_runs
    private String completedNodeIds;   // JSON array
    private int currentWave;           // номер волны
    private String createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getCompletedNodeIds() { return completedNodeIds; }
    public void setCompletedNodeIds(String completedNodeIds) { this.completedNodeIds = completedNodeIds; }

    public int getCurrentWave() { return currentWave; }
    public void setCurrentWave(int currentWave) { this.currentWave = currentWave; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
