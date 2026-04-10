package com.agent.orchestrator.model;

import java.util.Map;

public class ExecutionRecord {
    private String id;
    private String schemaId;
    private String schemaName;
    private long startTime;
    private long endTime;
    private long totalTimeMs;
    private int totalNodes;
    private int completedNodes;
    private String status; // "completed", "failed", "cancelled"
    private Map<String, NodeResult> nodeResults;

    public static class NodeResult {
        private String nodeId;
        private String nodeName;
        private String result;
        private long durationMs;
        private String status;

        public String getNodeId() { return nodeId; }
        public void setNodeId(String nodeId) { this.nodeId = nodeId; }
        public String getNodeName() { return nodeName; }
        public void setNodeName(String nodeName) { this.nodeName = nodeName; }
        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }
        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

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
    public Map<String, NodeResult> getNodeResults() { return nodeResults; }
    public void setNodeResults(Map<String, NodeResult> nodeResults) { this.nodeResults = nodeResults; }
}
