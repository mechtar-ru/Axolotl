package com.agent.orchestrator.model;

/**
 * Результат выполнения одного узла схемы в рамках конкретного запуска.
 * Персистентная запись — сохраняется после каждого завершённого узла.
 */
public class NodeExecution {
    private String id;
    private String runId;        // FK → execution_runs
    private String nodeId;       // ID узла в схеме
    private String nodeName;     // denormalized для отображения
    private String nodeType;     // denormalized (agent, source, output, etc.)
    private String status;       // pending | running | completed | failed | skipped
    private long tokensUsed;
    private long durationMs;
    private int toolCalls;
    private String error;
    private String inputSummary;   // JSON snapshot
    private String outputSummary;  // JSON snapshot
    private String filesWritten;   // JSON array [{path, description}]
    private String configHash;     // SHA256 конфига узла на момент выполнения
    private String startedAt;
    private String completedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }

    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getTokensUsed() { return tokensUsed; }
    public void setTokensUsed(long tokensUsed) { this.tokensUsed = tokensUsed; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public int getToolCalls() { return toolCalls; }
    public void setToolCalls(int toolCalls) { this.toolCalls = toolCalls; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getInputSummary() { return inputSummary; }
    public void setInputSummary(String inputSummary) { this.inputSummary = inputSummary; }

    public String getOutputSummary() { return outputSummary; }
    public void setOutputSummary(String outputSummary) { this.outputSummary = outputSummary; }

    public String getFilesWritten() { return filesWritten; }
    public void setFilesWritten(String filesWritten) { this.filesWritten = filesWritten; }

    public String getConfigHash() { return configHash; }
    public void setConfigHash(String configHash) { this.configHash = configHash; }

    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }

    public String getCompletedAt() { return completedAt; }
    public void setCompletedAt(String completedAt) { this.completedAt = completedAt; }
}
