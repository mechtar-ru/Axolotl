package com.agent.orchestrator.repository;

import com.agent.orchestrator.graph.model.GraphCheckpoint;
import com.agent.orchestrator.graph.model.GraphExecutionRun;
import com.agent.orchestrator.graph.model.GraphExecutionRecord;
import com.agent.orchestrator.graph.model.GraphNodeExecution;
import com.agent.orchestrator.graph.repository.Neo4jCheckpointRepository;
import com.agent.orchestrator.graph.repository.Neo4jExecutionRecordRepository;
import com.agent.orchestrator.graph.repository.Neo4jExecutionRunRepository;
import com.agent.orchestrator.graph.repository.Neo4jNodeExecutionRepository;
import com.agent.orchestrator.model.ExecutionCheckpoint;
import com.agent.orchestrator.model.ExecutionRun;
import com.agent.orchestrator.model.ExecutionRecord;
import com.agent.orchestrator.model.NodeExecution;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Repository for execution persistence backed by Neo4j.
 * Stores ExecutionRun, NodeExecution, Checkpoint, and ExecutionRecord
 * as separate Neo4j nodes linked via properties.
 * <p>
 * All write methods throw RuntimeException on failure so callers
 * can detect and handle persistence errors. Optimistic locking
 * failures are retried up to 3 times with backoff.
 */
@Repository
public class ExecutionRepository {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRepository.class);
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BACKOFF_MS = 100;

    private final Neo4jExecutionRunRepository runRepo;
    private final Neo4jNodeExecutionRepository nodeExecRepo;
    private final Neo4jCheckpointRepository checkpointRepo;
    private final Neo4jExecutionRecordRepository recordRepo;

    private static String formatInstant(Instant instant) {
        return instant != null ? instant.toString() : null;
    }

    private static Instant parseInstant(String str) {
        if (str == null || str.isEmpty()) return null;
        try {
            return Instant.parse(str);
        } catch (DateTimeParseException e) {
            log.warn("Failed to parse instant: {}", str);
            return null;
        }
    }

    public ExecutionRepository(
            Neo4jExecutionRunRepository runRepo,
            Neo4jNodeExecutionRepository nodeExecRepo,
            Neo4jCheckpointRepository checkpointRepo,
            Neo4jExecutionRecordRepository recordRepo,
            ObjectMapper jsonMapper) {
        this.runRepo = runRepo;
        this.nodeExecRepo = nodeExecRepo;
        this.checkpointRepo = checkpointRepo;
        this.recordRepo = recordRepo;
        this.jsonMapper = jsonMapper;
        log.info("ExecutionRepository initialized (Neo4j-backed)");
    }

    // ────────── ExecutionRun CRUD ──────────

    public void createRun(ExecutionRun run) {
        withRetry(() -> runRepo.save(toGraphRun(run)));
    }

    public void updateRunStatus(String id, String status, String error) {
        withRetry(() -> runRepo.findById(id).ifPresent(graphRun -> {
            graphRun.setStatus(status);
            graphRun.setError(error);
            graphRun.setUpdatedAt(java.time.Instant.now());
            runRepo.save(graphRun);
        }));
    }

    public void updateRunCompleted(String id, String status, long totalTokens, double estimatedCost) {
        withRetry(() -> runRepo.findById(id).ifPresent(graphRun -> {
            Instant now = java.time.Instant.now();
            graphRun.setStatus(status);
            graphRun.setTotalTokens(totalTokens);
            graphRun.setEstimatedCost(estimatedCost);
            graphRun.setUpdatedAt(now);
            graphRun.setCompletedAt(now);
            runRepo.save(graphRun);
        }));
    }

    public void updateRunGeneratedFiles(String id, List<String> generatedFiles) {
        withRetry(() -> runRepo.findById(id).ifPresent(graphRun -> {
            graphRun.setGeneratedFilesJson(listToJson(generatedFiles));
            graphRun.setUpdatedAt(java.time.Instant.now());
            runRepo.save(graphRun);
        }));
    }

    public ExecutionRun getRun(String id) {
        try {
            return runRepo.findById(id).map(this::toPocoRun).orElse(null);
        } catch (Exception e) {
            log.error("Error reading run {}: {}", id, e.getMessage(), e);
            return null;
        }
    }

    public List<ExecutionRun> getRunsBySchema(String schemaId) {
        try {
            return runRepo.findBySchemaIdOrderByStartedAtDesc(schemaId)
                    .stream().map(this::toPocoRun).toList();
        } catch (Exception e) {
            log.error("Error reading runs for schema {}: {}", schemaId, e.getMessage(), e);
            return List.of();
        }
    }

    public List<ExecutionRun> findByStatus(String status) {
        try {
            return runRepo.findByStatus(status)
                    .stream().map(this::toPocoRun).toList();
        } catch (Exception e) {
            log.error("Error reading runs by status {}: {}", status, e.getMessage(), e);
            return List.of();
        }
    }

    public ExecutionRun getLatestRunBySchemaAndStatus(String schemaId, String status) {
        try {
            return runRepo.findLatestBySchemaIdAndStatus(schemaId, status)
                    .map(this::toPocoRun).orElse(null);
        } catch (Exception e) {
            log.error("Error finding latest run {} status {}: {}", schemaId, status, e.getMessage(), e);
            return null;
        }
    }

    /** Returns the most recent ExecutionRun for a schema (any status), or null if none exist. */
    public ExecutionRun getLatestRunBySchema(String schemaId) {
        try {
            return getRunsBySchema(schemaId).stream().findFirst().orElse(null);
        } catch (Exception e) {
            log.error("Error fetching latest run for schema {}: {}", schemaId, e.getMessage(), e);
            return null;
        }
    }

    public boolean hasActiveRun(String schemaId) {
        try {
            return runRepo.hasActiveRun(schemaId);
        } catch (Exception e) {
            log.error("Error checking active run: {}", e.getMessage(), e);
            return false;
        }
    }

    /** Atomically claims a paused run by setting its status to 'resuming'.
     *  Returns the claimed ExecutionRun or null if no paused run exists. */
    public ExecutionRun claimPausedRun(String schemaId) {
        try {
            return runRepo.claimPausedRun(schemaId)
                    .map(this::toPocoRun).orElse(null);
        } catch (Exception e) {
            log.error("Error claiming paused run for schema {}: {}", schemaId, e.getMessage(), e);
            return null;
        }
    }

    /** Returns the last N completed runs for a schema (ordered by most recent first). */
    public List<ExecutionRun> getCompletedRuns(String schemaId, int limit) {
        try {
            return runRepo.findCompletedBySchemaId(schemaId, Math.min(limit, 10))
                    .stream().map(this::toPocoRun).toList();
        } catch (Exception e) {
            log.error("Error reading completed runs for schema {}: {}", schemaId, e.getMessage(), e);
            return List.of();
        }
    }

    public ExecutionRun getRunById(String runId) {
        try {
            return runRepo.findById(runId).map(this::toPocoRun).orElse(null);
        } catch (Exception e) {
            log.error("Error fetching run {}: {}", runId, e.getMessage(), e);
            return null;
        }
    }

    // ────────── Stage-level persistence (atomic Cypher, no read-modify-write race) ──────────

    public void updateRunStageStatus(String runId, String stageId, String status) {
        withRetry(() -> runRepo.findById(runId).ifPresent(g -> {
            Map<String, String> map = jsonToMap(g.getStageStatusJson());
            map.put(stageId, status);
            g.setStageStatusJson(mapToJson(map));
            g.setUpdatedAt(java.time.Instant.now());
            runRepo.save(g);
        }));
    }

    public void updateRunStageOutput(String runId, String stageId, String output) {
        withRetry(() -> runRepo.findById(runId).ifPresent(g -> {
            Map<String, String> map = jsonToMap(g.getStageOutputsJson());
            map.put(stageId, output);
            g.setStageOutputsJson(mapToJson(map));
            g.setUpdatedAt(java.time.Instant.now());
            runRepo.save(g);
        }));
    }

    public void updateRunResumeIndex(String runId, int resumeIndex) {
        withRetry(() -> runRepo.updateStatusAndResumeIndex(runId, null, resumeIndex));
    }

    /**
     * Atomically sets both status and resumeIndex in a single Cypher query.
     * Use instead of calling updateRunResumeIndex + updateRunStatus separately
     * to avoid inconsistent state if the process crashes between calls.
     */
    public void updateRunPaused(String runId, int resumeIndex) {
        withRetry(() -> runRepo.updateStatusAndResumeIndex(runId, "paused", resumeIndex));
    }

    /**
     * Updates ONLY the resumeIndex in Neo4j without touching the status field.
     * Use this instead of updateRunResumeIndex when you need to preserve the
     * existing status (e.g., 'paused' during persistResumeState).
     */
    public void updateRunResumeIndexOnly(String runId, int resumeIndex) {
        withRetry(() -> runRepo.updateResumeIndexOnly(runId, resumeIndex));
    }

    /**
     * Releases a claimed (resuming) paused run back to 'paused' status.
     * Called when resumePipeline fails unexpectedly, preventing the run
     * from being stuck permanently in 'resuming'.
     */
    public void releasePausedRun(String schemaId) {
        try {
            runRepo.releasePausedRun(schemaId);
        } catch (Exception e) {
            log.error("Error releasing paused run for schema {}: {}", schemaId, e.getMessage(), e);
        }
    }

    // ────────── Stale Run Cleanup & Deletion ──────────

    /**
     * Claims a specific paused run by ID (sets it to 'resuming').
     * Returns the claimed run, or null if not found or not in paused state.
     */
    public ExecutionRun claimSpecificRun(String runId) {
        try {
            Optional<GraphExecutionRun> result = runRepo.claimSpecificRun(runId);
            if (result.isPresent()) {
                return toPocoRun(result.get());
            }
            log.warn("No paused run found with id: {}", runId);
            return null;
        } catch (Exception e) {
            log.error("Error claiming specific run {}: {}", runId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Marks all runs with status='resuming' for a schema back to 'paused'.
     * Returns the number of released runs.
     */
    public int releaseStaleRuns(String schemaId) {
        try {
            long count = runRepo.releaseStaleRuns(schemaId);
            log.info("Released {} stale runs for schema {}", count, schemaId);
            return (int) count;
        } catch (Exception e) {
            log.error("Error releasing stale runs for schema {}: {}", schemaId, e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Deletes an ExecutionRun and all its NodeExecution records.
     * Does not throw on failure — logs and wraps as RuntimeException.
     */
    @Transactional
    public void deleteRun(String runId) {
        try {
            // Delete child node executions first, then the run itself
            runRepo.deleteNodeExecutionsByRunId(runId);
            runRepo.deleteById(runId);
            log.info("Deleted run {} and its node executions", runId);
        } catch (Exception e) {
            log.error("Error deleting run {}: {}", runId, e.getMessage(), e);
            throw new RuntimeException("Failed to delete run " + runId, e);
        }
    }

    // ────────── NodeExecution CRUD ──────────

    public void createNodeExecution(NodeExecution ne) {
        withRetry(() -> nodeExecRepo.save(toGraphNodeExec(ne)));
    }

    public void updateNodeExecution(String id, String status, String outputSummary,
                                      long tokensUsed, long durationMs, int toolCalls, String error) {
        withRetry(() -> nodeExecRepo.findById(id).ifPresent(graph -> {
            graph.setStatus(status);
            graph.setOutputSummary(outputSummary);
            graph.setTokensUsed(tokensUsed);
            graph.setDurationMs(durationMs);
            graph.setToolCalls(toolCalls);
            graph.setError(error);
            graph.setCompletedAt(java.time.Instant.now());
            nodeExecRepo.save(graph);
        }));
    }

    public void updateNodeExecution(String id, String status, String outputSummary,
                                      long tokensUsed, long durationMs, int toolCalls,
                                      String error, String reasoning) {
        withRetry(() -> nodeExecRepo.findById(id).ifPresent(graph -> {
            graph.setStatus(status);
            graph.setOutputSummary(outputSummary);
            graph.setTokensUsed(tokensUsed);
            graph.setDurationMs(durationMs);
            graph.setToolCalls(toolCalls);
            graph.setError(error);
            graph.setReasoning(reasoning);
            graph.setCompletedAt(java.time.Instant.now());
            nodeExecRepo.save(graph);
        }));
    }

    public void updateNodeExecutionWithFiles(String id, String status, String outputSummary,
                                              long tokensUsed, long durationMs, int toolCalls,
                                              String filesWritten, String error) {
        withRetry(() -> nodeExecRepo.findById(id).ifPresent(graph -> {
            graph.setStatus(status);
            graph.setOutputSummary(outputSummary);
            graph.setTokensUsed(tokensUsed);
            graph.setDurationMs(durationMs);
            graph.setToolCalls(toolCalls);
            graph.setFilesWritten(filesWritten);
            graph.setError(error);
            graph.setCompletedAt(java.time.Instant.now());
            nodeExecRepo.save(graph);
        }));
    }

    public List<NodeExecution> getNodeExecutionsByRun(String runId) {
        try {
            return nodeExecRepo.findByRunIdOrderByStartedAtAsc(runId)
                    .stream().map(this::toPocoNodeExec).toList();
        } catch (Exception e) {
            log.error("Error reading node_executions for run {}: {}", runId, e.getMessage(), e);
            return List.of();
        }
    }

    // ────────── Checkpoint CRUD ──────────

    public void saveCheckpoint(ExecutionCheckpoint checkpoint) {
        withRetry(() -> checkpointRepo.save(toGraphCheckpoint(checkpoint)));
    }

    public ExecutionCheckpoint getLatestCheckpoint(String runId) {
        try {
            return checkpointRepo.findLatestByRunId(runId)
                    .map(this::toPocoCheckpoint).orElse(null);
        } catch (Exception e) {
            log.error("Error reading latest checkpoint for run {}: {}", runId, e.getMessage(), e);
            return null;
        }
    }

    public List<ExecutionCheckpoint> getCheckpointsByRun(String runId) {
        try {
            return checkpointRepo.findByRunIdOrderByCreatedAtAsc(runId)
                    .stream().map(this::toPocoCheckpoint).toList();
        } catch (Exception e) {
            log.error("Error reading checkpoints for run {}: {}", runId, e.getMessage(), e);
            return List.of();
        }
    }

    // ────────── ExecutionRecord (history) CRUD ──────────

    public void saveExecutionRecord(ExecutionRecord record) {
        withRetry(() -> recordRepo.save(toGraphRecord(record)));
    }

    public List<ExecutionRecord> getExecutionRecordsBySchema(String schemaId) {
        try {
            return recordRepo.findBySchemaIdOrderByStartTimeDesc(schemaId)
                    .stream().map(this::toPocoRecord).toList();
        } catch (Exception e) {
            log.error("Error reading records for schema {}: {}", schemaId, e.getMessage(), e);
            return List.of();
        }
    }

    public List<ExecutionRecord> getAllExecutionRecords() {
        try {
            return recordRepo.findTop50ByOrderByStartTimeDesc()
                    .stream().map(this::toPocoRecord).toList();
        } catch (Exception e) {
            log.error("Error reading all execution records: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public void deleteExecutionRecord(String id) {
        try {
            recordRepo.deleteById(id);
            log.info("Deleted execution record {}", id);
        } catch (Exception e) {
            log.error("Error deleting execution record {}: {}", id, e.getMessage(), e);
            throw new RuntimeException("Failed to delete execution record " + id, e);
        }
    }

    public void deleteExecutionRecordsOlderThan(long cutoffTimestamp) {
        withRetry(() -> {
            recordRepo.deleteRecordsOlderThan(cutoffTimestamp / 1000);
            log.info("Deleted execution records older than cutoff={}", cutoffTimestamp);
        });
    }

    public void deleteRunsOlderThan(long cutoffTimestampMs) {
        try {
            long cutoffSeconds = cutoffTimestampMs / 1000;
            runRepo.deleteRunsOlderThan(String.valueOf(cutoffSeconds));
            log.info("Deleted runs older than cutoff={} ({} ms → {} s)", cutoffTimestampMs, cutoffTimestampMs, cutoffSeconds);
        } catch (Exception e) {
            log.error("Error deleting old runs: {}", e.getMessage(), e);
        }
    }

    // ────────── Retry helper for writes ──────────

    private void withRetry(Runnable operation) {
        Exception lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                operation.run();
                return;
            } catch (OptimisticLockingFailureException e) {
                lastException = e;
                if (attempt < MAX_RETRIES - 1) {
                    log.warn("Optimistic lock conflict (attempt {}/{}), retrying...", attempt + 1, MAX_RETRIES);
                    try { Thread.sleep(RETRY_BACKOFF_MS * (attempt + 1)); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while retrying optimistic lock", e);
                    }
                }
            } catch (Exception e) {
                log.error("Error in Neo4j write: {}", e.getMessage(), e);
                throw new RuntimeException("Neo4j write failed", e);
            }
        }
        // All retries exhausted for optimistic locking failure
        log.error("Optimistic lock conflict after {} retries", MAX_RETRIES);
        throw new RuntimeException("Neo4j write failed after retries", lastException);
    }

    // ────────── Mapping: POJO ↔ Graph entity ──────────

    private final ObjectMapper jsonMapper;

    private String mapToJson(Map<String, String> map) {
        try {
            return jsonMapper.writeValueAsString(map != null ? map : new HashMap<>());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize map to JSON", e);
            return "{}";
        }
    }

    private String listToJson(List<String> list) {
        try {
            return jsonMapper.writeValueAsString(list != null ? list : new ArrayList<>());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize list to JSON", e);
            return "[]";
        }
    }

    private Map<String, String> jsonToMap(String json) {
        try {
            return jsonMapper.readValue(json != null ? json : "{}",
                    jsonMapper.getTypeFactory().constructMapType(HashMap.class, String.class, String.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize map from JSON: {}", json, e);
            return new HashMap<>();
        }
    }

    private List<String> jsonToStringList(String json) {
        try {
            if (json == null || json.isBlank()) return new ArrayList<>();
            return jsonMapper.readValue(json,
                    jsonMapper.getTypeFactory().constructCollectionType(ArrayList.class, String.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize string list from JSON: {}", json, e);
            return new ArrayList<>();
        }
    }

    private GraphExecutionRun toGraphRun(ExecutionRun r) {
        GraphExecutionRun g = new GraphExecutionRun(r.getId(), r.getSchemaId(), r.getStatus(), r.getMode());
        g.setTotalTokens(r.getTotalTokens());
        g.setEstimatedCost(r.getEstimatedCost());
        g.setError(r.getError());
        g.setResumesFrom(r.getResumesFrom());
        g.setVersion(r.getVersion());
        g.setStartedAt(r.getStartedAt());
        g.setUpdatedAt(r.getUpdatedAt());
        g.setCompletedAt(r.getCompletedAt());
        g.setStageStatusJson(mapToJson(r.getStageStatus()));
        g.setStageOutputsJson(mapToJson(r.getStageOutputs()));
        g.setResumeIndex(r.getResumeIndex());
        g.setGeneratedFilesJson(listToJson(r.getGeneratedFiles()));
        return g;
    }

    private ExecutionRun toPocoRun(GraphExecutionRun g) {
        ExecutionRun r = new ExecutionRun();
        r.setId(g.getId());
        r.setSchemaId(g.getSchemaId());
        r.setStatus(g.getStatus());
        r.setMode(g.getMode());
        r.setTotalTokens(g.getTotalTokens());
        r.setEstimatedCost(g.getEstimatedCost());
        r.setError(g.getError());
        r.setResumesFrom(g.getResumesFrom());
        r.setVersion(g.getVersion());
        r.setStartedAt(g.getStartedAt());
        r.setUpdatedAt(g.getUpdatedAt());
        r.setCompletedAt(g.getCompletedAt());
        r.setStageStatus(jsonToMap(g.getStageStatusJson()));
        r.setStageOutputs(jsonToMap(g.getStageOutputsJson()));
        r.setResumeIndex(g.getResumeIndex());
        r.setGeneratedFiles(jsonToStringList(g.getGeneratedFilesJson()));
        return r;
    }

    private GraphNodeExecution toGraphNodeExec(NodeExecution n) {
        GraphNodeExecution g = new GraphNodeExecution();
        g.setId(n.getId());
        g.setRunId(n.getRunId());
        g.setNodeId(n.getNodeId());
        g.setNodeName(n.getNodeName());
        g.setNodeType(n.getNodeType());
        g.setStatus(n.getStatus());
        g.setTokensUsed(n.getTokensUsed());
        g.setDurationMs(n.getDurationMs());
        g.setToolCalls(n.getToolCalls());
        g.setError(n.getError());
        g.setInputSummary(n.getInputSummary());
        g.setOutputSummary(n.getOutputSummary());
        g.setFilesWritten(n.getFilesWritten());
        g.setConfigHash(n.getConfigHash());
        g.setStartedAt(n.getStartedAt());
        g.setCompletedAt(n.getCompletedAt());
        g.setReasoning(n.getReasoning());
        return g;
    }

    private NodeExecution toPocoNodeExec(GraphNodeExecution g) {
        NodeExecution n = new NodeExecution();
        n.setId(g.getId());
        n.setRunId(g.getRunId());
        n.setNodeId(g.getNodeId());
        n.setNodeName(g.getNodeName());
        n.setNodeType(g.getNodeType());
        n.setStatus(g.getStatus());
        n.setTokensUsed(g.getTokensUsed());
        n.setDurationMs(g.getDurationMs());
        n.setToolCalls(g.getToolCalls());
        n.setError(g.getError());
        n.setInputSummary(g.getInputSummary());
        n.setOutputSummary(g.getOutputSummary());
        n.setFilesWritten(g.getFilesWritten());
        n.setConfigHash(g.getConfigHash());
        n.setStartedAt(g.getStartedAt());
        n.setCompletedAt(g.getCompletedAt());
        n.setReasoning(g.getReasoning());
        return n;
    }

    private GraphCheckpoint toGraphCheckpoint(ExecutionCheckpoint c) {
        GraphCheckpoint g = new GraphCheckpoint();
        g.setId(c.getId());
        g.setRunId(c.getRunId());
        g.setCompletedNodeIds(c.getCompletedNodeIds());
        g.setCurrentWave(c.getCurrentWave());
        g.setCreatedAt(c.getCreatedAt());
        return g;
    }

    private ExecutionCheckpoint toPocoCheckpoint(GraphCheckpoint g) {
        ExecutionCheckpoint c = new ExecutionCheckpoint();
        c.setId(g.getId());
        c.setRunId(g.getRunId());
        c.setCompletedNodeIds(g.getCompletedNodeIds());
        c.setCurrentWave(g.getCurrentWave());
        c.setCreatedAt(g.getCreatedAt());
        return c;
    }

    private GraphExecutionRecord toGraphRecord(ExecutionRecord r) {
        GraphExecutionRecord g = new GraphExecutionRecord();
        g.setId(r.getId());
        g.setSchemaId(r.getSchemaId());
        g.setSchemaName(r.getSchemaName());
        g.setStartTime(r.getStartTime());
        g.setEndTime(r.getEndTime());
        g.setTotalTimeMs(r.getTotalTimeMs());
        g.setTotalNodes(r.getTotalNodes());
        g.setCompletedNodes(r.getCompletedNodes());
        g.setStatus(r.getStatus());
        g.setTotalTokens(r.getTotalTokens());
        g.setEstimatedCost(r.getEstimatedCost());
        return g;
    }

    private ExecutionRecord toPocoRecord(GraphExecutionRecord g) {
        ExecutionRecord r = new ExecutionRecord();
        r.setId(g.getId());
        r.setSchemaId(g.getSchemaId());
        r.setSchemaName(g.getSchemaName());
        r.setStartTime(g.getStartTime());
        r.setEndTime(g.getEndTime());
        r.setTotalTimeMs(g.getTotalTimeMs());
        r.setTotalNodes(g.getTotalNodes());
        r.setCompletedNodes(g.getCompletedNodes());
        r.setStatus(g.getStatus());
        r.setTotalTokens(g.getTotalTokens());
        r.setEstimatedCost(g.getEstimatedCost());
        return r;
    }
}
