package com.agent.orchestrator.service;

import com.agent.orchestrator.model.ExecutionRecord;
import com.agent.orchestrator.model.ExecutionRun;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.NodeExecution;
import com.agent.orchestrator.model.SchemaValidationResult;
import com.agent.orchestrator.model.WorkflowSchema;

import java.util.List;
import java.util.Map;

/**
 * Service interface for managing WorkflowSchema CRUD, execution, review lifecycle,
 * pipeline management, and schema import/export.
 */
public interface SchemaService {

    // ────────────────── Re-exported constants ──────────────────

    String ARCHITECT_ANALYST_PROMPT = NodeExecutor.ARCHITECT_ANALYST_PROMPT;
    String FEATURE_DESIGNER_PROMPT = NodeExecutor.FEATURE_DESIGNER_PROMPT;
    String TASK_BREAKDOWN_PROMPT = NodeExecutor.TASK_BREAKDOWN_PROMPT;
    String PLANNING_WORKFLOW_USER_PROMPT = NodeExecutor.PLANNING_WORKFLOW_USER_PROMPT;

    // ────────────────── CRUD ──────────────────

    List<WorkflowSchema> getAllSchemas();

    List<WorkflowSchema> getSchemasByUserId(String userId);

    Map<String, List<WorkflowSchema>> getSchemasGrouped(String userId);

    List<WorkflowSchema> getRecentSchemas(String userId, int limit);

    WorkflowSchema getSchema(String id);

    WorkflowSchema createSchema(WorkflowSchema schema);

    WorkflowSchema updateSchema(String id, WorkflowSchema incoming);

    void deleteSchema(String id);

    void batchDeleteSchemas(List<String> ids);

    void updateLastRunAt(String schemaId);

    // ────────────────── Export / Import ──────────────────

    WorkflowSchema exportSchema(String id);

    String exportToMermaid(String id);

    String exportToPython(String id);

    WorkflowSchema importSchema(WorkflowSchema schema, String userId);

    // ────────────────── Execution ──────────────────

    SchemaValidationResult validateSchema(String id);

    void executeSchema(String id);

    /** Execute schema with optional session-specific input (overrides source node's sourceData) */
    default void executeSchema(String id, String sessionInput) {
        executeSchema(id);
    }

    void cancelExecution(String id);

    // ────────────────── Execution Runs / Resilience ──────────────────

    List<ExecutionRun> findExecutionRuns(String schemaId);

    ExecutionRun getPausedRun(String schemaId);

    void resumeExecution(String schemaId);

    void resumeExecution(String schemaId, WorkflowSchema schema);

    void resumeExecution(String schemaId, WorkflowSchema schema, String parentRunId);

    void resumeExecution(String schemaId, String runId);

    List<NodeExecution> getExecutionNodes(String runId);

    // ────────────────── History ──────────────────

    List<ExecutionRecord> getExecutionHistory(String schemaId);

    List<ExecutionRecord> getAllExecutionHistory();

    String getOutputFileContent(String schemaId, String nodeId);

    // ────────────────── Review Feedback ──────────────────

    void handleReviewFeedback(String executionId, String nodeId, String feedback, List<Map<String, Object>> history);

    void handleReviewApprove(String executionId, String nodeId);

    void handleReviewApprove(String executionId, String nodeId, String schemaId);

    void handleReviewReject(String executionId, String nodeId);

    void handleReviewReject(String executionId, String nodeId, String schemaId);

    void handleDiffsApprove(String executionId, String nodeId);

    void handleDiffsReject(String executionId, String nodeId);

    // ────────────────── Execution Results ──────────────────

    Map<String, String> getExecutionResults(String executionId);

    Map<String, Object> getGeneratedFiles(String schemaId);

    // ────────────────── Config hash ──────────────────

    String computeConfigHash(Node node, WorkflowSchema schema);

    // ────────────────── Utilities ──────────────────

    static boolean isTestSchema(WorkflowSchema schema) {
        if (schema == null || schema.getName() == null) return false;
        String name = schema.getName().toLowerCase();
        return name.startsWith("test-") || name.startsWith("pw-review") || name.startsWith("debug-")
            || name.startsWith("check-") || name.startsWith("premortem-") || name.startsWith("pipeline-")
            || name.startsWith("final-") || name.startsWith("valid-")
            || name.contains("-debug") || name.contains("-test");
    }

    Map<String, Object> generateSchemaFromPrompt(String prompt, String model);
}
