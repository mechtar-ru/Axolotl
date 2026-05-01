package com.agent.orchestrator.analytics;

/**
 * Predefined task types for workflow classification.
 * Used by TaskTypeClassifier to categorize workflows and by ModelPreferenceTracker for model routing.
 */
public enum TaskType {
    TEXT_GENERATION("Text Generation", "Workflows that generate text output using agent nodes"),
    DATA_PROCESSING("Data Processing", "Workflows that transform or process data"),
    DECISION_MAKING("Decision Making", "Workflows that make decisions based on conditions"),
    REASONING("Reasoning", "Workflows with multiple agent nodes for complex reasoning"),
    SUMMARIZATION("Summarization", "Workflows that condense information into summaries"),
    TRANSLATION("Translation", "Workflows that translate content between languages"),
    CODE_GENERATION("Code Generation", "Workflows that generate or manipulate code"),
    QUESTION_ANSWERING("Question Answering", "Workflows that answer questions from source data");

    private final String displayName;
    private final String description;

    TaskType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
