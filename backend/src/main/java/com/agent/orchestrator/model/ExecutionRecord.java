package com.agent.orchestrator.model;

import lombok.Data;
import java.time.Instant;
import java.util.Map;

@Data
public class ExecutionRecord {
    private String id;
    private String schemaId;
    private String schemaName;
    private Instant startTime;
    private Instant endTime;
    private long totalTimeMs;
    private int totalNodes;
    private int completedNodes;
    private String status; // "completed", "failed", "cancelled"
    private int totalTokens;
    private double estimatedCost;
    private Map<String, NodeResult> nodeResults;

    @Data
    public static class NodeResult {
        private String nodeId;
        private String nodeName;
        private String result;
        private long durationMs;
        private String status;
    }
}
