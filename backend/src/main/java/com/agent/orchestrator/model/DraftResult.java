package com.agent.orchestrator.model;

import lombok.Data;

/**
 * Result of a draft node execution.
 * Contains the draft type, file path where the artifact was written,
 * and a summary of its content.
 */
@Data
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
}
