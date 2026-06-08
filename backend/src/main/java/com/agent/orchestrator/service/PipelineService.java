package com.agent.orchestrator.service;

import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.model.ExecutionRun;
import com.agent.orchestrator.model.SchemaValidationResult;
import com.agent.orchestrator.model.Pipeline;
import com.agent.orchestrator.model.Stage;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.ExecutionCheckpoint;
import com.agent.orchestrator.model.ExecutionRecord;
import com.agent.orchestrator.model.ExecutionError;
import com.agent.orchestrator.model.NodeExecution;
import com.agent.orchestrator.model.Task;
import com.agent.orchestrator.model.Plan;
import com.agent.orchestrator.model.PlanStep;
import com.agent.orchestrator.model.PlanStepStatus;
import com.agent.orchestrator.model.PlanLevel;
import com.agent.orchestrator.model.Priority;
import com.agent.orchestrator.model.ProjectType;
import com.agent.orchestrator.model.AppModel;
import com.agent.orchestrator.model.Agent;
import com.agent.orchestrator.model.ApiKey;
import com.agent.orchestrator.model.CustomLlmEndpoint;
import com.agent.orchestrator.model.DraftResult;
import com.agent.orchestrator.model.ExecutionMode;
import com.agent.orchestrator.model.ExecutionRecord;
import com.agent.orchestrator.model.ExecutionRun;
import com.agent.orchestrator.model.NodeExecution;
import com.agent.orchestrator.model.SchemaValidationResult;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for PipelineService - orchestrates multi-stage pipeline execution.
 */
public interface PipelineService {

    /**
     * Build pipeline nodes for a schema.
     */
    WorkflowSchema buildPipelineNodes(String schemaId);

    /**
     * Execute a pipeline for the given schema.
     */
    @Transactional
    void executePipeline(String schemaId);

    /**
     * Execute derived stages for a schema.
     */
    @Transactional
    void executeDerivedStages(String schemaId, WorkflowSchema schema, List<Stage> stages);

    /**
     * Retry a failed pipeline from the latest run.
     */
    @Transactional
    void retryPipeline(String schemaId);

    /**
     * Retry a failed pipeline from a specific run.
     */
    @Transactional
    void retryPipeline(String schemaId, String runId);

    /**
     * Resume a paused pipeline from the latest run.
     */
    @Transactional
    void resumePipeline(String schemaId);

    /**
     * Resume a paused pipeline from a specific run.
     */
    @Transactional
    void resumePipeline(String schemaId, String runId);

    /**
     * Cancel a running pipeline.
     */
    void cancelPipeline(String schemaId);

    /**
     * Get stage results for a schema.
     */
    Map<String, String> getStageResults(String schemaId);

    /**
     * Check if a pipeline is running.
     */
    boolean isPipelineRunning(String schemaId);

    /**
     * Create a default pipeline for an app type.
     */
    static Pipeline createDefaultPipeline(String appType, String description) {
        return PipelineFactory.createDefaultPipeline(appType, description);
    }

    /**
     * Create a new app pipeline (9-stage 4-phase).
     */
    static Pipeline createAppPipeline(String appType, String description) {
        return PipelineFactory.createAppPipeline(appType, description);
    }

    /**
     * Create a minimal pipeline for quick execution.
     */
    static Pipeline createMinimalPipeline(String appType, String description) {
        return PipelineFactory.createMinimalPipeline(appType, description);
    }

    /**
     * Expand TDD stages in a pipeline.
     */
    static void expandTddStages(Pipeline pipeline) {
        PipelineFactory.expandTddStages(pipeline);
    }

    /**
     * Create Stage objects from the schema's canvas nodes/edges.
     */
    static List<Stage> createStagesFromNodes(WorkflowSchema schema) {
        return PipelineFactory.createStagesFromNodes(schema);
    }
}