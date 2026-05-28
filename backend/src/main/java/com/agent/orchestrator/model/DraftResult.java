package com.agent.orchestrator.model;

/**
 * Result of a draft node execution.
 * Contains the draft type, file path where the artifact was written,
 * and a summary of its content.
 */
public class DraftResult {
    private String draftType;
    private String filePath;
    private String summary;

    public DraftResult() {}

    public DraftResult(String draftType, String filePath, String summary) {
        this.draftType = draftType;
        this.filePath = filePath;
        this.summary = summary;
    }

    public String getDraftType() { return draftType; }
    public void setDraftType(String draftType) { this.draftType = draftType; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
}
