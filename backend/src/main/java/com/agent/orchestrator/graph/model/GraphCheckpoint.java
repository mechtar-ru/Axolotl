package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;

@Node("Checkpoint")
public class GraphCheckpoint {

    @Id
    @Property("id")
    private String id;

    @Property("runId")
    private String runId;

    @Property("completedNodeIds")
    private String completedNodeIds;

    @Property("currentWave")
    private int currentWave;

    @Property("createdAt")
    private String createdAt;

    public GraphCheckpoint() {}

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
