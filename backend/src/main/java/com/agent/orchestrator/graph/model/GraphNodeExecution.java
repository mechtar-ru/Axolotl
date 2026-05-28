package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;

@Node("NodeExecution")
public class GraphNodeExecution {

    @Id
    @Property("id")
    private String id;

    @Property("runId")
    private String runId;

    @Property("nodeId")
    private String nodeId;

    @Property("nodeName")
    private String nodeName;

    @Property("nodeType")
    private String nodeType;

    @Property("status")
    private String status;

    @Property("tokensUsed")
    private long tokensUsed;

    @Property("durationMs")
    private long durationMs;

    @Property("toolCalls")
    private int toolCalls;

    @Property("error")
    private String error;

    @Property("inputSummary")
    private String inputSummary;

    @Property("outputSummary")
    private String outputSummary;

    @Property("filesWritten")
    private String filesWritten;

    @Property("configHash")
    private String configHash;

    @Property("startedAt")
    private String startedAt;

    @Property("completedAt")
    private String completedAt;

    @Property("reasoning")
    private String reasoning;

    public GraphNodeExecution() {}

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

    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }
}
