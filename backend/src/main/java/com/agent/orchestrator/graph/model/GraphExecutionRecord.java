package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;

@Node("ExecutionRecord")
public class GraphExecutionRecord {

    @Id
    @Property("id")
    private String id;

    @Property("schemaId")
    private String schemaId;

    @Property("schemaName")
    private String schemaName;

    @Property("startTime")
    private long startTime;

    @Property("endTime")
    private long endTime;

    @Property("totalTimeMs")
    private long totalTimeMs;

    @Property("totalNodes")
    private int totalNodes;

    @Property("completedNodes")
    private int completedNodes;

    @Property("status")
    private String status;

    @Property("totalTokens")
    private int totalTokens;

    @Property("estimatedCost")
    private double estimatedCost;

    public GraphExecutionRecord() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSchemaId() { return schemaId; }
    public void setSchemaId(String schemaId) { this.schemaId = schemaId; }

    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }

    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    public long getEndTime() { return endTime; }
    public void setEndTime(long endTime) { this.endTime = endTime; }

    public long getTotalTimeMs() { return totalTimeMs; }
    public void setTotalTimeMs(long totalTimeMs) { this.totalTimeMs = totalTimeMs; }

    public int getTotalNodes() { return totalNodes; }
    public void setTotalNodes(int totalNodes) { this.totalNodes = totalNodes; }

    public int getCompletedNodes() { return completedNodes; }
    public void setCompletedNodes(int completedNodes) { this.completedNodes = completedNodes; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }

    public double getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(double estimatedCost) { this.estimatedCost = estimatedCost; }
}
