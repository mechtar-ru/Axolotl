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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * Repository for execution persistence backed by Neo4j.
 * Stores ExecutionRun, NodeExecution, Checkpoint, and ExecutionRecord
 * as separate Neo4j nodes linked via properties.
 */
@Repository
public class ExecutionRepository {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRepository.class);

    private final Neo4jExecutionRunRepository runRepo;
    private final Neo4jNodeExecutionRepository nodeExecRepo;
    private final Neo4jCheckpointRepository checkpointRepo;
    private final Neo4jExecutionRecordRepository recordRepo;

    public ExecutionRepository(
            Neo4jExecutionRunRepository runRepo,
            Neo4jNodeExecutionRepository nodeExecRepo,
            Neo4jCheckpointRepository checkpointRepo,
            Neo4jExecutionRecordRepository recordRepo) {
        this.runRepo = runRepo;
        this.nodeExecRepo = nodeExecRepo;
        this.checkpointRepo = checkpointRepo;
        this.recordRepo = recordRepo;
        log.info("ExecutionRepository initialized (Neo4j-backed)");
    }

    // ────────── ExecutionRun CRUD ──────────

    public void createRun(ExecutionRun run) {
        try {
            runRepo.save(toGraphRun(run));
        } catch (Exception e) {
            log.error("Error creating execution run {}: {}", run.getId(), e.getMessage());
        }
    }

    public void updateRunStatus(String id, String status, String error) {
        try {
            runRepo.findById(id).ifPresent(graphRun -> {
                graphRun.setStatus(status);
                graphRun.setError(error);
                graphRun.setUpdatedAt(java.time.Instant.now().toString());
                runRepo.save(graphRun);
            });
        } catch (Exception e) {
            log.error("Error updating run status {}: {}", id, e.getMessage());
        }
    }

    public void updateRunCompleted(String id, String status, long totalTokens, double estimatedCost) {
        try {
            runRepo.findById(id).ifPresent(graphRun -> {
                String now = java.time.Instant.now().toString();
                graphRun.setStatus(status);
                graphRun.setTotalTokens(totalTokens);
                graphRun.setEstimatedCost(estimatedCost);
                graphRun.setUpdatedAt(now);
                graphRun.setCompletedAt(now);
                runRepo.save(graphRun);
            });
        } catch (Exception e) {
            log.error("Error updating run completion {}: {}", id, e.getMessage());
        }
    }

    public ExecutionRun getRun(String id) {
        try {
            return runRepo.findById(id).map(this::toPocoRun).orElse(null);
        } catch (Exception e) {
            log.error("Error reading run {}: {}", id, e.getMessage());
            return null;
        }
    }

    public List<ExecutionRun> getRunsBySchema(String schemaId) {
        try {
            return runRepo.findBySchemaIdOrderByStartedAtDesc(schemaId)
                    .stream().map(this::toPocoRun).toList();
        } catch (Exception e) {
            log.error("Error reading runs for schema {}: {}", schemaId, e.getMessage());
            return List.of();
        }
    }

    public ExecutionRun getLatestRunBySchemaAndStatus(String schemaId, String status) {
        try {
            return runRepo.findLatestBySchemaIdAndStatus(schemaId, status)
                    .map(this::toPocoRun).orElse(null);
        } catch (Exception e) {
            log.error("Error finding latest run {} status {}: {}", schemaId, status, e.getMessage());
            return null;
        }
    }

    public boolean hasActiveRun(String schemaId) {
        try {
            return runRepo.hasActiveRun(schemaId);
        } catch (Exception e) {
            log.error("Error checking active run: {}", e.getMessage());
            return false;
        }
    }

    // ────────── NodeExecution CRUD ──────────

    public void createNodeExecution(NodeExecution ne) {
        try {
            nodeExecRepo.save(toGraphNodeExec(ne));
        } catch (Exception e) {
            log.error("Error creating node_execution: {}", e.getMessage());
        }
    }

    public void updateNodeExecution(String id, String status, String outputSummary,
                                     long tokensUsed, long durationMs, int toolCalls, String error) {
        try {
            nodeExecRepo.findById(id).ifPresent(graph -> {
                graph.setStatus(status);
                graph.setOutputSummary(outputSummary);
                graph.setTokensUsed(tokensUsed);
                graph.setDurationMs(durationMs);
                graph.setToolCalls(toolCalls);
                graph.setError(error);
                graph.setCompletedAt(java.time.Instant.now().toString());
                nodeExecRepo.save(graph);
            });
        } catch (Exception e) {
            log.error("Error updating node_execution {}: {}", id, e.getMessage());
        }
    }

    public void updateNodeExecutionWithFiles(String id, String status, String outputSummary,
                                              long tokensUsed, long durationMs, int toolCalls,
                                              String filesWritten, String error) {
        try {
            nodeExecRepo.findById(id).ifPresent(graph -> {
                graph.setStatus(status);
                graph.setOutputSummary(outputSummary);
                graph.setTokensUsed(tokensUsed);
                graph.setDurationMs(durationMs);
                graph.setToolCalls(toolCalls);
                graph.setFilesWritten(filesWritten);
                graph.setError(error);
                graph.setCompletedAt(java.time.Instant.now().toString());
                nodeExecRepo.save(graph);
            });
        } catch (Exception e) {
            log.error("Error updating node_execution {}: {}", id, e.getMessage());
        }
    }

    public List<NodeExecution> getNodeExecutionsByRun(String runId) {
        try {
            return nodeExecRepo.findByRunIdOrderByStartedAtAsc(runId)
                    .stream().map(this::toPocoNodeExec).toList();
        } catch (Exception e) {
            log.error("Error reading node_executions for run {}: {}", runId, e.getMessage());
            return List.of();
        }
    }

    // ────────── Checkpoint CRUD ──────────

    public void saveCheckpoint(ExecutionCheckpoint checkpoint) {
        try {
            checkpointRepo.save(toGraphCheckpoint(checkpoint));
        } catch (Exception e) {
            log.error("Error saving checkpoint: {}", e.getMessage());
        }
    }

    public ExecutionCheckpoint getLatestCheckpoint(String runId) {
        try {
            return checkpointRepo.findLatestByRunId(runId)
                    .map(this::toPocoCheckpoint).orElse(null);
        } catch (Exception e) {
            log.error("Error reading latest checkpoint for run {}: {}", runId, e.getMessage());
            return null;
        }
    }

    public List<ExecutionCheckpoint> getCheckpointsByRun(String runId) {
        try {
            return checkpointRepo.findByRunIdOrderByCreatedAtAsc(runId)
                    .stream().map(this::toPocoCheckpoint).toList();
        } catch (Exception e) {
            log.error("Error reading checkpoints for run {}: {}", runId, e.getMessage());
            return List.of();
        }
    }

    // ────────── ExecutionRecord (history) CRUD ──────────

    public void saveExecutionRecord(ExecutionRecord record) {
        try {
            recordRepo.save(toGraphRecord(record));
        } catch (Exception e) {
            log.error("Error saving execution record: {}", e.getMessage());
        }
    }

    public List<ExecutionRecord> getExecutionRecordsBySchema(String schemaId) {
        try {
            return recordRepo.findBySchemaIdOrderByStartTimeDesc(schemaId)
                    .stream().map(this::toPocoRecord).toList();
        } catch (Exception e) {
            log.error("Error reading records for schema {}: {}", schemaId, e.getMessage());
            return List.of();
        }
    }

    public List<ExecutionRecord> getAllExecutionRecords() {
        try {
            return recordRepo.findTop50ByOrderByStartTimeDesc()
                    .stream().map(this::toPocoRecord).toList();
        } catch (Exception e) {
            log.error("Error reading all execution records: {}", e.getMessage());
            return List.of();
        }
    }

    public void deleteExecutionRecordsOlderThan(long cutoffTimestamp) {
        try {
            recordRepo.deleteRecordsOlderThan(cutoffTimestamp);
            log.info("Удалены записи выполнения старше cutoff={}", cutoffTimestamp);
        } catch (Exception e) {
            log.error("Ошибка при удалении старых записей выполнения: {}", e.getMessage());
        }
    }

    // ────────── Mapping: POJO ↔ Graph entity ──────────

    private GraphExecutionRun toGraphRun(ExecutionRun r) {
        GraphExecutionRun g = new GraphExecutionRun(r.getId(), r.getSchemaId(), r.getStatus(), r.getMode());
        g.setTotalTokens(r.getTotalTokens());
        g.setEstimatedCost(r.getEstimatedCost());
        g.setError(r.getError());
        g.setResumesFrom(r.getResumesFrom());
        g.setStartedAt(r.getStartedAt());
        g.setUpdatedAt(r.getUpdatedAt());
        g.setCompletedAt(r.getCompletedAt());
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
        r.setStartedAt(g.getStartedAt());
        r.setUpdatedAt(g.getUpdatedAt());
        r.setCompletedAt(g.getCompletedAt());
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
