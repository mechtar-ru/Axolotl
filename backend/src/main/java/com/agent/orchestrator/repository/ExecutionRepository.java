package com.agent.orchestrator.repository;

import com.agent.orchestrator.config.DbConfig;
import com.agent.orchestrator.model.ExecutionCheckpoint;
import com.agent.orchestrator.model.ExecutionRun;
import com.agent.orchestrator.model.NodeExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Repository
public class ExecutionRepository {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRepository.class);

    private final String dbUrl;

    public ExecutionRepository(DbConfig dbConfig) {
        this.dbUrl = dbConfig.getDbUrl();
        createTables();
    }

    private void createTables() {
        String runsSql = """
            CREATE TABLE IF NOT EXISTS execution_runs (
                id TEXT PRIMARY KEY,
                schema_id TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'running',
                mode TEXT NOT NULL DEFAULT 'EXECUTE',
                total_tokens INTEGER DEFAULT 0,
                estimated_cost REAL DEFAULT 0.0,
                error TEXT,
                resumes_from TEXT,
                started_at TEXT,
                updated_at TEXT,
                completed_at TEXT,
                FOREIGN KEY (schema_id) REFERENCES schemas(id)
            )
            """;

        String nodesSql = """
            CREATE TABLE IF NOT EXISTS node_executions (
                id TEXT PRIMARY KEY,
                run_id TEXT NOT NULL,
                node_id TEXT NOT NULL,
                node_name TEXT,
                node_type TEXT,
                status TEXT NOT NULL DEFAULT 'pending',
                tokens_used INTEGER DEFAULT 0,
                duration_ms INTEGER DEFAULT 0,
                tool_calls INTEGER DEFAULT 0,
                error TEXT,
                input_summary TEXT,
                output_summary TEXT,
                files_written TEXT,
                config_hash TEXT,
                started_at TEXT,
                completed_at TEXT,
                FOREIGN KEY (run_id) REFERENCES execution_runs(id)
            )
            """;

        String checkpointsSql = """
            CREATE TABLE IF NOT EXISTS execution_checkpoints (
                id TEXT PRIMARY KEY,
                run_id TEXT NOT NULL,
                completed_node_ids TEXT NOT NULL,
                current_wave INTEGER DEFAULT 0,
                created_at TEXT,
                FOREIGN KEY (run_id) REFERENCES execution_runs(id)
            )
            """;

        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {
            stmt.execute(runsSql);
            stmt.execute(nodesSql);
            stmt.execute(checkpointsSql);
            log.info("Таблицы execution_* созданы/проверены");
        } catch (SQLException e) {
            log.error("Ошибка создания таблиц execution_*: {}", e.getMessage());
        }
    }

    // ────────── ExecutionRun CRUD ──────────

    public void createRun(ExecutionRun run) {
        String sql = """
            INSERT INTO execution_runs (id, schema_id, status, mode, total_tokens, estimated_cost,
                                        error, resumes_from, started_at, updated_at, completed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, run.getId());
            pstmt.setString(2, run.getSchemaId());
            pstmt.setString(3, run.getStatus());
            pstmt.setString(4, run.getMode());
            pstmt.setLong(5, run.getTotalTokens());
            pstmt.setDouble(6, run.getEstimatedCost());
            pstmt.setString(7, run.getError());
            pstmt.setString(8, run.getResumesFrom());
            pstmt.setString(9, run.getStartedAt());
            pstmt.setString(10, run.getUpdatedAt());
            pstmt.setString(11, run.getCompletedAt());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Ошибка создания execution_run: {}", e.getMessage());
        }
    }

    public void updateRunStatus(String id, String status, String error) {
        String sql = "UPDATE execution_runs SET status = ?, error = ?, updated_at = ? WHERE id = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, status);
            pstmt.setString(2, error);
            pstmt.setString(3, java.time.Instant.now().toString());
            pstmt.setString(4, id);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Ошибка обновления статуса run {}: {}", id, e.getMessage());
        }
    }

    public void updateRunCompleted(String id, String status, long totalTokens, double estimatedCost) {
        String sql = """
            UPDATE execution_runs
            SET status = ?, total_tokens = ?, estimated_cost = ?, updated_at = ?, completed_at = ?
            WHERE id = ?
            """;
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            String now = java.time.Instant.now().toString();
            pstmt.setString(1, status);
            pstmt.setLong(2, totalTokens);
            pstmt.setDouble(3, estimatedCost);
            pstmt.setString(4, now);
            pstmt.setString(5, now);
            pstmt.setString(6, id);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Ошибка завершения run {}: {}", id, e.getMessage());
        }
    }

    public ExecutionRun getRun(String id) {
        String sql = "SELECT * FROM execution_runs WHERE id = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) return mapRun(rs);
            }
        } catch (SQLException e) {
            log.error("Ошибка чтения run {}: {}", id, e.getMessage());
        }
        return null;
    }

    public List<ExecutionRun> getRunsBySchema(String schemaId) {
        List<ExecutionRun> runs = new ArrayList<>();
        String sql = "SELECT * FROM execution_runs WHERE schema_id = ? ORDER BY started_at DESC";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, schemaId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) runs.add(mapRun(rs));
            }
        } catch (SQLException e) {
            log.error("Ошибка чтения runs для схемы {}: {}", schemaId, e.getMessage());
        }
        return runs;
    }

    /** Получить последний run схемы с указанным статусом */
    public ExecutionRun getLatestRunBySchemaAndStatus(String schemaId, String status) {
        String sql = "SELECT * FROM execution_runs WHERE schema_id = ? AND status = ? ORDER BY started_at DESC LIMIT 1";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, schemaId);
            pstmt.setString(2, status);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) return mapRun(rs);
            }
        } catch (SQLException e) {
            log.error("Ошибка поиска последнего run {} status {}: {}", schemaId, status, e.getMessage());
        }
        return null;
    }

    /** Проверить, есть ли активный запуск для схемы (running или paused) */
    public boolean hasActiveRun(String schemaId) {
        String sql = "SELECT COUNT(*) FROM execution_runs WHERE schema_id = ? AND (status = 'running' OR status = 'paused')";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, schemaId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            log.error("Ошибка проверки активного run: {}", e.getMessage());
        }
        return false;
    }

    // ────────── NodeExecution CRUD ──────────

    public void createNodeExecution(NodeExecution ne) {
        String sql = """
            INSERT INTO node_executions (id, run_id, node_id, node_name, node_type, status,
                                         tokens_used, duration_ms, tool_calls, error,
                                         input_summary, output_summary, files_written, config_hash,
                                         started_at, completed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, ne.getId());
            pstmt.setString(2, ne.getRunId());
            pstmt.setString(3, ne.getNodeId());
            pstmt.setString(4, ne.getNodeName());
            pstmt.setString(5, ne.getNodeType());
            pstmt.setString(6, ne.getStatus());
            pstmt.setLong(7, ne.getTokensUsed());
            pstmt.setLong(8, ne.getDurationMs());
            pstmt.setInt(9, ne.getToolCalls());
            pstmt.setString(10, ne.getError());
            pstmt.setString(11, ne.getInputSummary());
            pstmt.setString(12, ne.getOutputSummary());
            pstmt.setString(13, ne.getFilesWritten());
            pstmt.setString(14, ne.getConfigHash());
            pstmt.setString(15, ne.getStartedAt());
            pstmt.setString(16, ne.getCompletedAt());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Ошибка создания node_execution: {}", e.getMessage());
        }
    }

    public void updateNodeExecution(String id, String status, String outputSummary, long tokensUsed,
                                     long durationMs, int toolCalls, String error) {
        String sql = """
            UPDATE node_executions
            SET status = ?, output_summary = ?, tokens_used = ?, duration_ms = ?,
                tool_calls = ?, error = ?, completed_at = ?
            WHERE id = ?
            """;
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, status);
            pstmt.setString(2, outputSummary);
            pstmt.setLong(3, tokensUsed);
            pstmt.setLong(4, durationMs);
            pstmt.setInt(5, toolCalls);
            pstmt.setString(6, error);
            pstmt.setString(7, java.time.Instant.now().toString());
            pstmt.setString(8, id);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Ошибка обновления node_execution {}: {}", id, e.getMessage());
        }
    }

    /** Update node execution including files_written JSON. Backward-compatible addition. */
    public void updateNodeExecutionWithFiles(String id, String status, String outputSummary, long tokensUsed,
                                             long durationMs, int toolCalls, String filesWritten, String error) {
        String sql = """
            UPDATE node_executions
            SET status = ?, output_summary = ?, tokens_used = ?, duration_ms = ?,
                tool_calls = ?, files_written = ?, error = ?, completed_at = ?
            WHERE id = ?
            """;
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, status);
            pstmt.setString(2, outputSummary);
            pstmt.setLong(3, tokensUsed);
            pstmt.setLong(4, durationMs);
            pstmt.setInt(5, toolCalls);
            pstmt.setString(6, filesWritten);
            pstmt.setString(7, error);
            pstmt.setString(8, java.time.Instant.now().toString());
            pstmt.setString(9, id);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Ошибка обновления node_execution {}: {}", id, e.getMessage());
        }
    }

    public List<NodeExecution> getNodeExecutionsByRun(String runId) {
        List<NodeExecution> results = new ArrayList<>();
        String sql = "SELECT * FROM node_executions WHERE run_id = ? ORDER BY started_at ASC";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, runId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) results.add(mapNodeExecution(rs));
            }
        } catch (SQLException e) {
            log.error("Ошибка чтения node_executions для run {}: {}", runId, e.getMessage());
        }
        return results;
    }

    // ────────── Checkpoint CRUD ──────────

    public void saveCheckpoint(ExecutionCheckpoint checkpoint) {
        String sql = """
            INSERT INTO execution_checkpoints (id, run_id, completed_node_ids, current_wave, created_at)
            VALUES (?, ?, ?, ?, ?)
            """;
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, checkpoint.getId());
            pstmt.setString(2, checkpoint.getRunId());
            pstmt.setString(3, checkpoint.getCompletedNodeIds());
            pstmt.setInt(4, checkpoint.getCurrentWave());
            pstmt.setString(5, checkpoint.getCreatedAt());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Ошибка сохранения чекпоинта: {}", e.getMessage());
        }
    }

    public ExecutionCheckpoint getLatestCheckpoint(String runId) {
        String sql = "SELECT * FROM execution_checkpoints WHERE run_id = ? ORDER BY created_at DESC LIMIT 1";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, runId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) return mapCheckpoint(rs);
            }
        } catch (SQLException e) {
            log.error("Ошибка чтения последнего чекпоинта для run {}: {}", runId, e.getMessage());
        }
        return null;
    }

    public List<ExecutionCheckpoint> getCheckpointsByRun(String runId) {
        List<ExecutionCheckpoint> list = new ArrayList<>();
        String sql = "SELECT * FROM execution_checkpoints WHERE run_id = ? ORDER BY created_at ASC";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, runId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) list.add(mapCheckpoint(rs));
            }
        } catch (SQLException e) {
            log.error("Ошибка чтения чекпоинтов для run {}: {}", runId, e.getMessage());
        }
        return list;
    }

    // ────────── Row mappers ──────────

    private ExecutionRun mapRun(ResultSet rs) throws SQLException {
        ExecutionRun run = new ExecutionRun();
        run.setId(rs.getString("id"));
        run.setSchemaId(rs.getString("schema_id"));
        run.setStatus(rs.getString("status"));
        run.setMode(rs.getString("mode"));
        run.setTotalTokens(rs.getLong("total_tokens"));
        run.setEstimatedCost(rs.getDouble("estimated_cost"));
        run.setError(rs.getString("error"));
        run.setResumesFrom(rs.getString("resumes_from"));
        run.setStartedAt(rs.getString("started_at"));
        run.setUpdatedAt(rs.getString("updated_at"));
        run.setCompletedAt(rs.getString("completed_at"));
        return run;
    }

    private NodeExecution mapNodeExecution(ResultSet rs) throws SQLException {
        NodeExecution ne = new NodeExecution();
        ne.setId(rs.getString("id"));
        ne.setRunId(rs.getString("run_id"));
        ne.setNodeId(rs.getString("node_id"));
        ne.setNodeName(rs.getString("node_name"));
        ne.setNodeType(rs.getString("node_type"));
        ne.setStatus(rs.getString("status"));
        ne.setTokensUsed(rs.getLong("tokens_used"));
        ne.setDurationMs(rs.getLong("duration_ms"));
        ne.setToolCalls(rs.getInt("tool_calls"));
        ne.setError(rs.getString("error"));
        ne.setInputSummary(rs.getString("input_summary"));
        ne.setOutputSummary(rs.getString("output_summary"));
        ne.setFilesWritten(rs.getString("files_written"));
        ne.setConfigHash(rs.getString("config_hash"));
        ne.setStartedAt(rs.getString("started_at"));
        ne.setCompletedAt(rs.getString("completed_at"));
        return ne;
    }

    private ExecutionCheckpoint mapCheckpoint(ResultSet rs) throws SQLException {
        ExecutionCheckpoint cp = new ExecutionCheckpoint();
        cp.setId(rs.getString("id"));
        cp.setRunId(rs.getString("run_id"));
        cp.setCompletedNodeIds(rs.getString("completed_node_ids"));
        cp.setCurrentWave(rs.getInt("current_wave"));
        cp.setCreatedAt(rs.getString("created_at"));
        return cp;
    }
}
