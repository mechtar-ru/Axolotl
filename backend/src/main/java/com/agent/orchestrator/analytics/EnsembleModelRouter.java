package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.WorkflowSchema;

/**
 * Routes LLM calls to optimal models based on task type and performance history.
 * 
 * Integration: LlmService calls getModelForWorkflow(workflow, availableModels)
 * to get the best model for a given workflow.
 */


public class EnsembleModelRouter {
    private final TaskTypeClassifier taskTypeClassifier;
    private final ModelPreferenceTracker modelPreferenceTracker;
    private String manualOverride;

    public EnsembleModelRouter(TaskTypeClassifier taskTypeClassifier, ModelPreferenceTracker modelPreferenceTracker) {
        this.taskTypeClassifier = taskTypeClassifier;
        this.modelPreferenceTracker = modelPreferenceTracker;
    }

    /**
     * Gets the recommended model for a workflow.
     */
    public String getModelForWorkflow(WorkflowSchema workflow, java.util.List<String> availableModels) {
        // Manual override takes precedence
        if (manualOverride != null && availableModels.contains(manualOverride)) {
            return manualOverride;
        }

        // Classify task type
        TaskTypeClassifier.ClassificationResult classification = taskTypeClassifier.classify(workflow);
        TaskType taskType = classification.getTaskType();

        // Get recommended model from preference tracker
        return modelPreferenceTracker.getRecommendedModel(taskType, availableModels);
    }

    /**
     * Gets the task type for a workflow (for display/debugging).
     */
    public TaskType getTaskType(WorkflowSchema workflow) {
        return taskTypeClassifier.classify(workflow).getTaskType();
    }

    /**
     * Gets the confidence score for the current recommendation.
     */
    public double getRecommendationConfidence(WorkflowSchema workflow, String modelId) {
        TaskType taskType = taskTypeClassifier.classify(workflow).getTaskType();
        return modelPreferenceTracker.getModelConfidence(taskType, modelId);
    }

    /**
     * Sets a manual model override.
     */
    public void setManualOverride(String modelId) {
        this.manualOverride = modelId;
    }

    /**
     * Clears the manual override.
     */
    public void clearManualOverride() {
        this.manualOverride = null;
    }

    /**
     * Gets explanation for why a model was selected.
     */
    public String getRoutingExplanation(WorkflowSchema workflow, String modelId) {
        TaskTypeClassifier.ClassificationResult classification = taskTypeClassifier.classify(workflow);
        TaskType taskType = classification.getTaskType();
        double score = modelPreferenceTracker.getModelScore(taskType, modelId);
        
        return String.format("Model '%s' selected for task type '%s' with performance score %.2f",
            modelId, taskType, score);
    }
}