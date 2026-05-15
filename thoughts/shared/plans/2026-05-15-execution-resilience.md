# Execution Resilience & Recovery Implementation Plan

**Goal:** Persist execution state to SQLite so schemas can resume after token exhaustion, server crash, or browser close — without re-executing completed nodes.

**Architecture:** Three new SQLite tables (`execution_runs`, `node_executions`, `execution_checkpoints`) with raw JDBC `ExecutionRepository`. Each "Run" click creates an `execution_run` record. Per-node results persist as `node_executions`. Periodic checkpoints save after each topological wave. Resume creates a **new child Run** that inherits completed nodes via config-hash comparison — skipping identical nodes, re-executing changed/failed/pending ones. Frontend shows unified timeline with visual run separators and a resume banner.

**Design:** `thoughts/shared/designs/2026-05-15-execution-resilience-design.md`

**Key decisions (design gaps filled):**
- Config hash = SHA-256 of `node.getData()` JSON serialization (system prompt, tools, model) + connected edge IDs — computed in `NodeExecutor.executeNode()` before starting work
- Single active run enforcement: `executeSchema()` checks for PAUSED/RUNNING runs before starting a new one
- WebSocket `paused` event: new type with `{type: "paused", runId, completedNodes, totalNodes}`
- The existing in-memory `executionHistory` in SchemaService remains unchanged for backward compatibility — new runs API is additive

---

## Dependency Graph

```
Batch 1 (parallel): 1.1, 1.2, 1.3, 1.4 [models + repository - no deps]
                     ↓
Batch 2 (parallel): 2.1, 2.2, 2.3, 2.4 [service + controller + WS changes - depend on Batch 1]
                     ↓
Batch 3 (parallel): 3.1, 3.2, 3.3, 3.4 [frontend components + API - depend on Batch 2]
                     ↓
Batch 4 (parallel): 4.1, 4.2, 4.3, 4.4 [tests - depend on all]
```

---

## Batch 1: Foundation (parallel — 4 implementers)

All tasks in this batch have NO dependencies and run simultaneously.

### Task 1.1: ExecutionRun Model
**File:** `backend/src/main/java/com/agent/orchestrator/model/ExecutionRun.java`
**Test:** none (POJO — tested via repository integration)
**Depends:** none

```java
package com.agent.orchestrator.model;

import java.util.List;

/**
 * Сущность запуска выполнения схемы.
 * Один "клик по Run" = один ExecutionRun.
 * При возобновлении создаётся новый ExecutionRun со ссылкой на родительский.
 */
public class ExecutionRun {
    private String id;
    private String schemaId;
    private String status;       // running | paused | completed | failed | cancelled
    private String mode;         // EXECUTE | ANALYZE | DRY_RUN
    private long totalTokens;
    private double estimatedCost;
    private String error;
    private String resumesFrom;  // nullable FK → parent run id
    private String startedAt;
    private String updatedAt;
    private String completedAt;

    // Геттеры и сеттеры
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSchemaId() { return schemaId; }
    public void setSchemaId(String schemaId) { this.schemaId = schemaId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public long getTotalTokens() { return totalTokens; }
    public void setTotalTokens(long totalTokens) { this.totalTokens = totalTokens; }

    public double getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(double estimatedCost) { this.estimatedCost = estimatedCost; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getResumesFrom() { return resumesFrom; }
    public void setResumesFrom(String resumesFrom) { this.resumesFrom = resumesFrom; }

    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public String getCompletedAt() { return completedAt; }
    public void setCompletedAt(String completedAt) { this.completedAt = completedAt; }
}
```

**Commit:** `feat(model): add ExecutionRun entity`

---

### Task 1.2: NodeExecution Model
**File:** `backend/src/main/java/com/agent/orchestrator/model/NodeExecution.java`
**Test:** none (POJO — tested via repository integration)
**Depends:** none

```java
package com.agent.orchestrator.model;

/**
 * Результат выполнения одного узла схемы в рамках конкретного запуска.
 * Персистентная запись — сохраняется после каждого завершённого узла.
 */
public class NodeExecution {
    private String id;
    private String runId;        // FK → execution_runs
    private String nodeId;       // ID узла в схеме
    private String nodeName;     // denormalized для отображения
    private String nodeType;     // denormalized (agent, source, output, etc.)
    private String status;       // pending | running | completed | failed | skipped
    private long tokensUsed;
    private long durationMs;
    private int toolCalls;
    private String error;
    private String inputSummary;   // JSON snapshot
    private String outputSummary;  // JSON snapshot
    private String filesWritten;   // JSON array [{path, description}]
    private String configHash;     // SHA256 конфига узла на момент выполнения
    private String startedAt;
    private String completedAt;

    // Геттеры и сеттеры
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
}
```

**Commit:** `feat(model): add NodeExecution entity`

---

### Task 1.3: ExecutionCheckpoint Model
**File:** `backend/src/main/java/com/agent/orchestrator/model/ExecutionCheckpoint.java`
**Test:** none (POJO — tested via repository integration)
**Depends:** none

```java
package com.agent.orchestrator.model;

/**
 * Чекпоинт выполнения — сохраняется после каждой топологической волны.
 * Позволяет восстановить состояние при возобновлении после краша.
 */
public class ExecutionCheckpoint {
    private String id;
    private String runId;              // FK → execution_runs
    private String completedNodeIds;   // JSON array
    private int currentWave;           // номер волны
    private String createdAt;

    // Геттеры и сеттеры
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getCompletedNodeIds() { return completedNodeIds; }
    public void setCompletedNodeIds(String completedNodeIds) { this.completedNodeIds = completedNodeIds; }

    public int getCurrentWave() { return currentWave; }
    public void setCurrentWave(int currentWave) { this.currentWave = currentWave; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
```

**Commit:** `feat(model): add ExecutionCheckpoint entity`

---

### Task 1.4: ExecutionRepository (SQLite via raw JDBC)
**File:** `backend/src/main/java/com/agent/orchestrator/repository/ExecutionRepository.java`
**Test:** `backend/src/test/java/com/agent/orchestrator/repository/ExecutionRepositoryTest.java`
**Depends:** none (uses DbConfig from existing config)

**Pattern:** Matches `SchemaRepository.java` — constructor takes `DbConfig`, creates tables in constructor, uses `DriverManager.getConnection()` + `PreparedStatement` for all operations.

**Implementation:**

```java
package com.agent.orchestrator.repository;

import com.agent.orchestrator.config.DbConfig;
import com.agent.orchestrator.model.ExecutionCheckpoint;
import com.agent.orchestrator.model.ExecutionRun;
import com.agent.orchestrator.model.NodeExecution;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper mapper = new ObjectMapper();

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
```

**Test:**

```java
package com.agent.orchestrator.repository;

import com.agent.orchestrator.config.DbConfig;
import com.agent.orchestrator.model.ExecutionCheckpoint;
import com.agent.orchestrator.model.ExecutionRun;
import com.agent.orchestrator.model.NodeExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionRepositoryTest {

    private ExecutionRepository repo;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        String dbPath = tempDir.resolve("test.db").toString();
        DbConfig config = new DbConfig(dbPath);
        repo = new ExecutionRepository(config);
    }

    @Test
    void createAndGetRun() {
        ExecutionRun run = new ExecutionRun();
        run.setId(UUID.randomUUID().toString());
        run.setSchemaId("schema-1");
        run.setStatus("running");
        run.setMode("EXECUTE");
        run.setStartedAt(java.time.Instant.now().toString());

        repo.createRun(run);

        ExecutionRun loaded = repo.getRun(run.getId());
        assertNotNull(loaded);
        assertEquals("running", loaded.getStatus());
        assertEquals("schema-1", loaded.getSchemaId());
    }

    @Test
    void updateRunStatus() {
        ExecutionRun run = new ExecutionRun();
        run.setId(UUID.randomUUID().toString());
        run.setSchemaId("schema-1");
        run.setStatus("running");
        run.setMode("EXECUTE");
        run.setStartedAt(java.time.Instant.now().toString());
        repo.createRun(run);

        repo.updateRunStatus(run.getId(), "paused", "Token limit");

        ExecutionRun loaded = repo.getRun(run.getId());
        assertEquals("paused", loaded.getStatus());
        assertEquals("Token limit", loaded.getError());
    }

    @Test
    void getRunsBySchema() {
        String schemaId = "schema-test";
        ExecutionRun r1 = new ExecutionRun();
        r1.setId(UUID.randomUUID().toString());
        r1.setSchemaId(schemaId);
        r1.setStatus("completed");
        r1.setMode("EXECUTE");
        r1.setStartedAt(java.time.Instant.now().toString());

        ExecutionRun r2 = new ExecutionRun();
        r2.setId(UUID.randomUUID().toString());
        r2.setSchemaId(schemaId);
        r2.setStatus("paused");
        r2.setMode("EXECUTE");
        r2.setStartedAt(java.time.Instant.now().toString());

        repo.createRun(r1);
        repo.createRun(r2);

        List<ExecutionRun> runs = repo.getRunsBySchema(schemaId);
        assertEquals(2, runs.size());
    }

    @Test
    void hasActiveRun() {
        String schemaId = "schema-active";
        ExecutionRun run = new ExecutionRun();
        run.setId(UUID.randomUUID().toString());
        run.setSchemaId(schemaId);
        run.setStatus("paused");
        run.setMode("EXECUTE");
        run.setStartedAt(java.time.Instant.now().toString());
        repo.createRun(run);

        assertTrue(repo.hasActiveRun(schemaId));
    }

    @Test
    void createAndGetNodeExecution() {
        String runId = UUID.randomUUID().toString();
        NodeExecution ne = new NodeExecution();
        ne.setId(UUID.randomUUID().toString());
        ne.setRunId(runId);
        ne.setNodeId("node-1");
        ne.setNodeName("Source");
        ne.setNodeType("source");
        ne.setStatus("completed");
        ne.setDurationMs(1500L);
        ne.setOutputSummary("{\"result\": \"ok\"}");
        ne.setStartedAt(java.time.Instant.now().toString());
        repo.createNodeExecution(ne);

        List<NodeExecution> nodes = repo.getNodeExecutionsByRun(runId);
        assertEquals(1, nodes.size());
        assertEquals("node-1", nodes.get(0).getNodeId());
        assertEquals("completed", nodes.get(0).getStatus());
    }

    @Test
    void updateNodeExecution() {
        String runId = UUID.randomUUID().toString();
        NodeExecution ne = new NodeExecution();
        ne.setId(UUID.randomUUID().toString());
        ne.setRunId(runId);
        ne.setNodeId("node-1");
        ne.setNodeName("Agent");
        ne.setNodeType("agent");
        ne.setStatus("running");
        ne.setStartedAt(java.time.Instant.now().toString());
        repo.createNodeExecution(ne);

        repo.updateNodeExecution(ne.getId(), "completed", "Done!", 150L, 2000L, 5, null);

        List<NodeExecution> nodes = repo.getNodeExecutionsByRun(runId);
        assertEquals(1, nodes.size());
        assertEquals("completed", nodes.get(0).getStatus());
        assertEquals("Done!", nodes.get(0).getOutputSummary());
        assertEquals(150L, nodes.get(0).getTokensUsed());
    }

    @Test
    void saveAndGetLatestCheckpoint() {
        String runId = UUID.randomUUID().toString();
        ExecutionCheckpoint cp = new ExecutionCheckpoint();
        cp.setId(UUID.randomUUID().toString());
        cp.setRunId(runId);
        cp.setCompletedNodeIds("[\"n1\",\"n2\"]");
        cp.setCurrentWave(1);
        cp.setCreatedAt(java.time.Instant.now().toString());
        repo.saveCheckpoint(cp);

        ExecutionCheckpoint loaded = repo.getLatestCheckpoint(runId);
        assertNotNull(loaded);
        assertEquals("[\"n1\",\"n2\"]", loaded.getCompletedNodeIds());
        assertEquals(1, loaded.getCurrentWave());
    }

    @Test
    void getLatestRunBySchemaAndStatus() {
        String schemaId = "schema-status-test";
        ExecutionRun r1 = new ExecutionRun();
        r1.setId(UUID.randomUUID().toString());
        r1.setSchemaId(schemaId);
        r1.setStatus("completed");
        r1.setMode("EXECUTE");
        r1.setStartedAt(java.time.Instant.now().toString());

        ExecutionRun r2 = new ExecutionRun();
        r2.setId(UUID.randomUUID().toString());
        r2.setSchemaId(schemaId);
        r2.setStatus("paused");
        r2.setMode("EXECUTE");
        r2.setStartedAt(java.time.Instant.now().toString());

        repo.createRun(r1);
        repo.createRun(r2);

        ExecutionRun found = repo.getLatestRunBySchemaAndStatus(schemaId, "paused");
        assertNotNull(found);
        assertEquals("paused", found.getStatus());
    }
}
```

**Verify:** `cd backend && mvn test -Dtest=ExecutionRepositoryTest`
**Commit:** `feat(repository): add ExecutionRepository for run/node/checkpoint persistence`

---

## Batch 2: Core Logic Changes (parallel — 4 implementers)

All tasks in this batch depend on Batch 1 completing (need models + repository).

### Task 2.1: SchemaService — executeSchema modification + resumeExecution + getExecutionRuns
**File:** `backend/src/main/java/com/agent/orchestrator/service/SchemaService.java` (MODIFY)
**Test:** `backend/src/test/java/com/agent/orchestrator/service/SchemaServiceResilienceTest.java` (NEW)
**Depends:** 1.1, 1.2, 1.3, 1.4 (uses ExecutionRun, NodeExecution, ExecutionCheckpoint, ExecutionRepository)

**Changes to SchemaService:**

1. Add `ExecutionRepository executionRepository` as constructor parameter
2. Add `findExecutionRuns(schemaId)` method
3. Add `getPausedRun(schemaId)` method
4. Modify `executeSchema()` to create ExecutionRun + NodeExecutions + save checkpoints after each wave
5. Add `resumeExecution(schemaId)` method
6. Add config hash computation helper

Key design decisions:
- Config hash = SHA-256 of `mapper.writeValueAsString(node.getData())` + connected edge source IDs
- `resumeExecution()` creates new child run, compares hashes, copies completed matching nodes as SKIPPED
- Checkpoints saved after each wave completion (in the wave loop after `CompletableFuture.allOf`)
- Single active run enforced: if `executionRepository.hasActiveRun(id)`, throw or log warning

**New constructor injection needed** — add `ExecutionRepository` parameter. The new test file covers the resume flow.

**Detailed modification points in SchemaService:**

```java
// 1. Add field + constructor parameter
private final ExecutionRepository executionRepository;

// Modify constructor:
public SchemaService(Neo4jSchemaRepository schemaRepository,
                     ExecutionWebSocketHandler webSocketHandler,
                     MemPalaceClient memPalaceClient,
                     SettingsService settingsService,
                     MetricsService metricsService,
                     NodeExecutor nodeExecutor,
                     SchemaExporter schemaExporter,
                     LlmService llmService,
                     PlanService planService,
                     ExecutionRepository executionRepository) {  // NEW
    // ... existing assignments ...
    this.executionRepository = executionRepository;
}
```

```java
// 2. Add new methods after the existing executionHistory section:

// ────────── Persisted Runs (Execution Resilience) ──────────

public List<ExecutionRun> findExecutionRuns(String schemaId) {
    return executionRepository.getRunsBySchema(schemaId);
}

public ExecutionRun getPausedRun(String schemaId) {
    return executionRepository.getLatestRunBySchemaAndStatus(schemaId, "paused");
}

public void resumeExecution(String schemaId) {
    WorkflowSchema schema = schemaRepository.findById(schemaId);
    if (schema == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Schema not found: " + schemaId);
    }

    ExecutionRun parentRun = executionRepository.getLatestRunBySchemaAndStatus(schemaId, "paused");
    if (parentRun == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "No paused execution found for schema: " + schemaId);
    }

    if (executionRepository.hasActiveRun(schemaId)) {
        log.warn("Schema {} already has an active run, cannot resume", schemaId);
        return;
    }

    // Get parent node executions
    List<NodeExecution> parentNodeExecs = executionRepository.getNodeExecutionsByRun(parentRun.getId());

    // Create child run
    String childRunId = UUID.randomUUID().toString();
    ExecutionRun childRun = new ExecutionRun();
    childRun.setId(childRunId);
    childRun.setSchemaId(schemaId);
    childRun.setStatus("running");
    childRun.setMode("EXECUTE");
    childRun.setResumesFrom(parentRun.getId());
    childRun.setStartedAt(Instant.now().toString());
    childRun.setUpdatedAt(Instant.now().toString());
    executionRepository.createRun(childRun);

    // Compute config hashes for current schema nodes
    List<Node> schemaNodes = schema.getNodes();
    if (schemaNodes == null) schemaNodes = new ArrayList<>();

    Map<String, String> currentConfigHashes = new HashMap<>();
    for (Node node : schemaNodes) {
        currentConfigHashes.put(node.getId(), computeConfigHash(node, schema));
    }

    // For each node, determine if it can be skipped or needs execution
    List<NodeExecution> newNodeExecs = new ArrayList<>();
    for (Node node : schemaNodes) {
        String currentHash = currentConfigHashes.get(node.getId());
        NodeExecution matched = parentNodeExecs.stream()
                .filter(ne -> ne.getNodeId().equals(node.getId()))
                .filter(ne -> "completed".equals(ne.getStatus()))
                .filter(ne -> currentHash.equals(ne.getConfigHash()))
                .findFirst().orElse(null);

        NodeExecution newNodeExec = new NodeExecution();
        newNodeExec.setId(UUID.randomUUID().toString());
        newNodeExec.setRunId(childRunId);
        newNodeExec.setNodeId(node.getId());
        newNodeExec.setNodeName(node.getName());
        newNodeExec.setNodeType(node.getType());
        newNodeExec.setStartedAt(Instant.now().toString());

        if (matched != null) {
            // Config matches — skip
            newNodeExec.setStatus("skipped");
            newNodeExec.setOutputSummary(matched.getOutputSummary());
            newNodeExec.setConfigHash(currentHash);
            newNodeExec.setCompletedAt(Instant.now().toString());
        } else {
            // New or changed node — execute
            newNodeExec.setStatus("pending");
            newNodeExec.setConfigHash(currentHash);
        }
        newNodeExecs.add(newNodeExec);
        executionRepository.createNodeExecution(newNodeExec);
    }

    log.info("Resuming schema {}: parentRun={}, childRun={}, {} nodes ({} skipped)",
            schemaId, parentRun.getId(), childRunId, newNodeExecs.size(),
            newNodeExecs.stream().filter(ne -> "skipped".equals(ne.getStatus())).count());

    // Execute the resumed workflow (same wave logic as executeSchema)
    // Reset node statuses
    if (schema.getNodes() != null) {
        for (Node node : schema.getNodes()) {
            NodeExecution exec = newNodeExecs.stream()
                    .filter(ne -> ne.getNodeId().equals(node.getId()))
                    .findFirst().orElse(null);
            if (exec != null && "skipped".equals(exec.getStatus())) {
                node.setStatus(Node.NodeStatus.COMPLETED);
            } else {
                node.setStatus(Node.NodeStatus.IDLE);
            }
        }
    }

    // Run the wave-based execution (wrapper that provides skipped node outputs as results)
    AtomicBoolean cancelFlag = new AtomicBoolean(false);
    cancelFlags.put(schemaId, cancelFlag);

    // Add skipped node results to nodeExecutor so downstream nodes can read them
    for (NodeExecution ne : newNodeExecs) {
        if ("skipped".equals(ne.getStatus()) && ne.getOutputSummary() != null) {
            nodeExecutor.getNodeResults()
                    .computeIfAbsent(schemaId, k -> new ConcurrentHashMap<>())
                    .put(ne.getNodeId(), ne.getOutputSummary());
        }
    }

    CompletableFuture<?> future = CompletableFuture.runAsync(
            () -> executeWorkflow(schema, cancelFlag), executionExecutor);
    runningExecutions.put(schemaId, future);
}

/**
 * Вычислить SHA-256 хеш конфигурации узла для детекции изменений.
 * Хеширует JSON data узла + IDs входящих рёбер.
 */
public String computeConfigHash(Node node, WorkflowSchema schema) {
    try {
        ObjectMapper hashMapper = new ObjectMapper();
        Map<String, Object> hashData = new LinkedHashMap<>();

        // Node data (system prompt, tools, model, etc.)
        if (node.getData() != null) {
            hashData.put("data", node.getData());
        }

        // Connected incoming edge IDs (affects data flow)
        List<String> incomingEdgeIds = new ArrayList<>();
        if (schema.getEdges() != null) {
            for (Edge edge : schema.getEdges()) {
                if (edge.getTarget().equals(node.getId())) {
                    incomingEdgeIds.add(edge.getSource());
                }
            }
        }
        collections.sort(incomingEdgeIds);
        hashData.put("incomingEdges", incomingEdgeIds);

        String json = hashMapper.writeValueAsString(hashData);
        return org.apache.commons.codec.digest.DigestUtils.sha256Hex(json);
    } catch (JsonProcessingException e) {
        log.error("Ошибка вычисления config hash: {}", e.getMessage());
        return UUID.randomUUID().toString(); // Fallback: force re-execution
    }
}
```

```java
// 3. Modify executeSchema() to create execution run + persist nodes:
// After the schema validation and before executeWorkflow call:

// Create ExecutionRun
String runId = UUID.randomUUID().toString();
ExecutionRun run = new ExecutionRun();
run.setId(runId);
run.setSchemaId(id);
run.setStatus("running");
run.setMode("EXECUTE");
run.setStartedAt(Instant.now().toString());
run.setUpdatedAt(Instant.now().toString());
executionRepository.createRun(run);

// Create NodeExecution for each node
if (schema.getNodes() != null) {
    for (Node node : schema.getNodes()) {
        NodeExecution ne = new NodeExecution();
        ne.setId(UUID.randomUUID().toString());
        ne.setRunId(runId);
        ne.setNodeId(node.getId());
        ne.setNodeName(node.getName());
        ne.setNodeType(node.getType());
        ne.setStatus("pending");
        ne.setConfigHash(computeConfigHash(node, schema));
        ne.setStartedAt(Instant.now().toString());
        executionRepository.createNodeExecution(ne);
    }
}
```

```java
// 4. Modify the wave loop in executeWorkflow to save checkpoints:
// After: CompletableFuture.allOf(futures.toArray(...)).join();
// Add: saveCheckpoint(schema, runId, completedCount, waveNum);

// And after all waves complete (before recordExecution), update run status:
// executionRepository.updateRunCompleted(runId, "completed", totalTokens, estimatedCost);
// In the cancel branch: executionRepository.updateRunStatus(runId, "cancelled", "Cancelled by user");

// On error in wave loop (catch block): executionRepository.updateRunStatus(runId, "paused", errorMsg);
```

```java
// 5. Save checkpoint helper:
private void saveCheckpoint(WorkflowSchema schema, String runId, int completedCount, int waveNum) {
    try {
        ExecutionCheckpoint cp = new ExecutionCheckpoint();
        cp.setId(UUID.randomUUID().toString());
        cp.setRunId(runId);
        cp.setCurrentWave(waveNum);
        cp.setCreatedAt(Instant.now().toString());

        List<String> completedIds = new ArrayList<>();
        if (schema.getNodes() != null) {
            for (Node node : schema.getNodes()) {
                if (node.getStatus() == Node.NodeStatus.COMPLETED) {
                    completedIds.add(node.getId());
                }
            }
        }
        cp.setCompletedNodeIds(new ObjectMapper().writeValueAsString(completedIds));
        executionRepository.saveCheckpoint(cp);
    } catch (Exception e) {
        log.warn("Ошибка сохранения чекпоинта: {}", e.getMessage());
    }
}
```

**Also update executeWorkflow error handling:** When an LLM error indicates token exhaustion (402/429), catch in the wave loop and:
```java
executionRepository.updateRunStatus(runId, "paused", "Token limit exceeded during wave " + waveNum);
```

**Note:** The `executionHistory` in-memory list stays unchanged for backward compat. New persisted data is additive.

**Test file:** `backend/src/test/java/com/agent/orchestrator/service/SchemaServiceResilienceTest.java`

```java
package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.model.*;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.agent.orchestrator.config.DbConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SchemaServiceResilienceTest {

    @Mock Neo4jSchemaRepository schemaRepository;
    @Mock ExecutionWebSocketHandler webSocketHandler;
    @Mock MemPalaceClient memPalaceClient;
    @Mock SettingsService settingsService;
    @Mock MetricsService metricsService;
    @Mock NodeExecutor nodeExecutor;
    @Mock SchemaExporter schemaExporter;
    @Mock LlmService llmService;

    private ExecutionRepository executionRepository;
    private SchemaService schemaService;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        String dbPath = tempDir.resolve("test.db").toString();
        DbConfig config = new DbConfig(dbPath);
        executionRepository = new ExecutionRepository(config);
        // Use spy for PlanService since it's optional
        PlanService planService = mock(PlanService.class);

        schemaService = new SchemaService(schemaRepository, webSocketHandler, memPalaceClient,
                settingsService, metricsService, nodeExecutor, schemaExporter, llmService,
                planService, executionRepository);
    }

    @Test
    void computeConfigHash_sameConfig_sameHash() {
        Node node1 = new Node();
        node1.setId("n1");
        node1.setName("Agent");
        node1.setType("agent");
        Node.NodeData data1 = new Node.NodeData();
        data1.setModel("gpt-4o");
        data1.setUserPrompt("Analyze");
        node1.setData(data1);

        Node node2 = new Node();
        node2.setId("n2");
        node2.setName("Agent");
        node2.setType("agent");
        Node.NodeData data2 = new Node.NodeData();
        data2.setModel("gpt-4o");
        data2.setUserPrompt("Analyze");
        node2.setData(data2);

        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("test-schema");
        schema.setNodes(List.of(node1, node2));
        schema.setEdges(List.of());

        String hash1 = schemaService.computeConfigHash(node1, schema);
        String hash2 = schemaService.computeConfigHash(node2, schema);

        assertEquals(hash1, hash2, "Одинаковая конфигурация → одинаковый хеш");
    }

    @Test
    void computeConfigHash_differentConfig_differentHash() {
        Node node1 = new Node();
        node1.setId("n1");
        node1.setType("agent");
        Node.NodeData data1 = new Node.NodeData();
        data1.setModel("gpt-4o");
        node1.setData(data1);

        Node node2 = new Node();
        node2.setId("n2");
        node2.setType("agent");
        Node.NodeData data2 = new Node.NodeData();
        data2.setModel("claude-3");
        node2.setData(data2);

        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("test-schema");
        schema.setNodes(List.of(node1, node2));
        schema.setEdges(List.of());

        String hash1 = schemaService.computeConfigHash(node1, schema);
        String hash2 = schemaService.computeConfigHash(node2, schema);

        assertNotEquals(hash1, hash2, "Разная конфигурация → разный хеш");
    }

    @Test
    void computeConfigHash_differentEdges_differentHash() {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("test-schema");

        Node node = new Node();
        node.setId("n1");
        node.setType("agent");
        Node.NodeData data = new Node.NodeData();
        data.setModel("gpt-4o");
        node.setData(data);
        schema.setNodes(List.of(node));

        Edge edge = new Edge();
        edge.setId("e1");
        edge.setSource("source-1");
        edge.setTarget("n1");
        schema.setEdges(List.of(edge));

        String hashWithEdge = schemaService.computeConfigHash(node, schema);

        schema.setEdges(List.of());
        String hashWithoutEdge = schemaService.computeConfigHash(node, schema);

        assertNotEquals(hashWithEdge, hashWithoutEdge,
                "Разные входящие рёбра → разный хеш");
    }

    @Test
    void findExecutionRuns_returnsRuns() {
        // Create a run via repository directly
        ExecutionRun run = new ExecutionRun();
        run.setId(UUID.randomUUID().toString());
        run.setSchemaId("schema-runs-test");
        run.setStatus("completed");
        run.setMode("EXECUTE");
        run.setStartedAt(java.time.Instant.now().toString());
        executionRepository.createRun(run);

        List<ExecutionRun> runs = schemaService.findExecutionRuns("schema-runs-test");
        assertFalse(runs.isEmpty());
        assertEquals("completed", runs.get(0).getStatus());
    }

    @Test
    void getPausedRun_returnsNullWhenNoPausedRun() {
        ExecutionRun run = new ExecutionRun();
        run.setId(UUID.randomUUID().toString());
        run.setSchemaId("schema-no-pause");
        run.setStatus("completed");
        run.setMode("EXECUTE");
        run.setStartedAt(java.time.Instant.now().toString());
        executionRepository.createRun(run);

        ExecutionRun paused = schemaService.getPausedRun("schema-no-pause");
        assertNull(paused);
    }

    @Test
    void getPausedRun_returnsPausedRun() {
        ExecutionRun run = new ExecutionRun();
        run.setId(UUID.randomUUID().toString());
        run.setSchemaId("schema-paused");
        run.setStatus("paused");
        run.setMode("EXECUTE");
        run.setStartedAt(java.time.Instant.now().toString());
        executionRepository.createRun(run);

        ExecutionRun paused = schemaService.getPausedRun("schema-paused");
        assertNotNull(paused);
        assertEquals("paused", paused.getStatus());
    }
}
```

**Verify:** `cd backend && mvn test -Dtest=SchemaServiceResilienceTest`
**Commit:** `feat(service): add run persistence, config hash, resume execution`

---

### Task 2.2: NodeExecutor — persist per-node results + handle 402/429
**File:** `backend/src/main/java/com/agent/orchestrator/service/NodeExecutor.java` (MODIFY)
**Test:** `backend/src/test/java/com/agent/orchestrator/service/NodeExecutorResilienceTest.java` (NEW)
**Depends:** 1.2, 1.4 (uses NodeExecution, ExecutionRepository)

**Changes to NodeExecutor:**

1. Add `ExecutionRepository executionRepository` as constructor parameter
2. After each node completes (around line 430-441), call `executionRepository.updateNodeExecution()` to persist result
3. In the error catch block (around line 443-456), detect 402/429 in error message → mark node FAILED, trigger run PAUSED
4. Add `currentRunId` tracking (passed from SchemaService or set before execution)
5. Store `currentRunId` → `executionRepository.updateNodeExecution(...)` mapping

**Approach:** Since NodeExecutor doesn't know the `runId`, SchemaService will set it via a new method `setCurrentRunId(schemaId, runId)` before starting execution. NodeExecutor stores this in a `ConcurrentHashMap<String, String>` mapping schemaId → runId.

```java
// New field
private final ExecutionRepository executionRepository;
private final Map<String, String> schemaRunIds = new ConcurrentHashMap<>();
private static final ObjectMapper executionMapper = new ObjectMapper();

// Constructor parameter added
public NodeExecutor(LlmService llmService, ExecutionWebSocketHandler webSocketHandler,
                    MemPalaceClient memPalaceClient, ToolExecutor toolExecutor,
                    TransformService transformService, Neo4jSchemaRepository schemaRepository,
                    PlanService planService, ProjectContextBuilder projectContextBuilder,
                    ExecutionRepository executionRepository) {
    // ... existing ...
    this.executionRepository = executionRepository;
}

// New method called by SchemaService before executeWorkflow
public void setCurrentRunId(String schemaId, String runId) {
    schemaRunIds.put(schemaId, runId);
}

// In executeNode(), before storing result at line 430-441, add:
String runId = schemaRunIds.get(schemaId);
if (runId != null && !"skipped".equals(node.getStatus())) {
    try {
        // Find the NodeExecution for this node
        List<NodeExecution> execs = executionRepository.getNodeExecutionsByRun(runId);
        NodeExecution ne = execs.stream()
                .filter(e -> e.getNodeId().equals(node.getId()))
                .findFirst().orElse(null);
        if (ne != null) {
            long durationMs = node.getData() != null && node.getData().getResult() != null
                    ? System.currentTimeMillis() - parseStartTime(ne.getStartedAt()) : 0;
            executionRepository.updateNodeExecution(
                    ne.getId(),
                    "completed",
                    node.getData() != null ? node.getData().getResult() : result,
                    0L,  // tokensUsed — estimate if available
                    durationMs,
                    0,   // toolCalls
                    null
            );
        }
    } catch (Exception e) {
        log.warn("Ошибка персистенции результата узла {}: {}", node.getId(), e.getMessage());
    }
}

// In the exception catch block, after setting node FAILED:
if (runId != null) {
    String errorMsg = e.getMessage();
    if (errorMsg != null && (errorMsg.contains("402") || errorMsg.contains("429")
            || errorMsg.contains("insufficient_quota") || errorMsg.contains("rate_limit"))) {
        executionRepository.updateRunStatus(runId, "paused",
                "Token limit exceeded: " + errorMsg);
        if (webSocketHandler != null) {
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "paused");
            msg.put("runId", runId);
            msg.put("error", errorMsg);
            webSocketHandler.sendLiveUpdate(schemaId, "CUSTOM", msg);
        }
    } else {
        // Mark node failed in DB
        try {
            List<NodeExecution> execs = executionRepository.getNodeExecutionsByRun(runId);
            NodeExecution ne = execs.stream()
                    .filter(e -> e.getNodeId().equals(node.getId()))
                    .findFirst().orElse(null);
            if (ne != null) {
                executionRepository.updateNodeExecution(ne.getId(), "failed",
                        null, 0L, 0L, 0, errorMsg);
            }
        } catch (Exception ex) {
            log.warn("Ошибка записи ошибки узла: {}", ex.getMessage());
        }
    }
}
```

The test file:

```java
package com.agent.orchestrator.service;

import com.agent.orchestrator.config.DbConfig;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.model.ExecutionRun;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.NodeExecution;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NodeExecutorResilienceTest {

    @Mock LlmService llmService;
    @Mock ExecutionWebSocketHandler webSocketHandler;
    @Mock MemPalaceClient memPalaceClient;
    @Mock ToolExecutor toolExecutor;
    @Mock TransformService transformService;
    @Mock Neo4jSchemaRepository schemaRepository;
    @Mock PlanService planService;
    @Mock ProjectContextBuilder projectContextBuilder;

    private ExecutionRepository executionRepository;
    private NodeExecutor nodeExecutor;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        String dbPath = tempDir.resolve("test.db").toString();
        DbConfig config = new DbConfig(dbPath);
        executionRepository = new ExecutionRepository(config);

        nodeExecutor = new NodeExecutor(llmService, webSocketHandler, memPalaceClient,
                toolExecutor, transformService, schemaRepository, planService,
                projectContextBuilder, executionRepository);
    }

    @Test
    void setCurrentRunId_storesMapping() {
        nodeExecutor.setCurrentRunId("schema-1", "run-1");
        // Verify via behavior — after this, node execution should persist
        assertNotNull(executionRepository.getRun("run-1")); // not created yet, that's SchemaService's job
    }

    @Test
    void errorWithTokenExhaustion_updatesRunStatus() {
        String schemaId = "schema-token";
        String runId = UUID.randomUUID().toString();

        // Create run
        ExecutionRun run = new ExecutionRun();
        run.setId(runId);
        run.setSchemaId(schemaId);
        run.setStatus("running");
        run.setMode("EXECUTE");
        run.setStartedAt(java.time.Instant.now().toString());
        executionRepository.createRun(run);

        nodeExecutor.setCurrentRunId(schemaId, runId);

        // Simulate node execution that throws 402 error
        Node node = new Node();
        node.setId("node-1");
        node.setName("LLM Agent");
        node.setType("agent");

        when(llmService.streamingChat(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("HTTP 402 Payment Required — insufficient_quota"));

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        nodeExecutor.executeNode(node, schemaId, cancelFlag,
                com.agent.orchestrator.model.ExecutionMode.EXECUTE, "gpt-4o");

        // Run should be paused
        ExecutionRun updatedRun = executionRepository.getRun(runId);
        assertEquals("paused", updatedRun.getStatus());
    }
}
```

**Verify:** `cd backend && mvn test -Dtest=NodeExecutorResilienceTest`
**Commit:** `feat(service): persist node results in NodeExecutor, handle 402/429 token exhaustion`

---

### Task 2.3: ExecutionWebSocketHandler — add sendPaused method
**File:** `backend/src/main/java/com/agent/orchestrator/websocket/ExecutionWebSocketHandler.java` (MODIFY)
**Test:** none (already tested via existing handler tests — just new method)
**Depends:** none (standalone addition)

**Add after `sendLiveUpdate` method:**

```java
/**
 * Отправить событие паузы выполнения (token exhaustion / rate limit).
 */
public void sendPaused(String schemaId, String runId, int completedNodes, int totalNodes, String error) {
    Map<String, Object> msg = baseMsg("paused", schemaId);
    msg.put("runId", runId);
    msg.put("completedNodes", completedNodes);
    msg.put("totalNodes", totalNodes);
    msg.put("error", error);
    sendMessage(schemaId, toJson(msg));
    log.warn("Выполнение приостановлено [{}]: {} ({}/{}) узлов завершено — {}",
            schemaId, runId, completedNodes, totalNodes, error);
}
```

**Also add WebSocket callback for `paused` in the frontend WebSocket handler (in the `switch` inside `useWebSocket.ts`):**

```typescript
case 'paused':
  callbacks?.onPaused?.(data);
  break;
```

And add to `WebSocketCallbacks` interface:
```typescript
onPaused?: (data: { schemaId: string; runId: string; completedNodes: number; totalNodes: number; error: string }) => void;
```

**Verify:** `cd backend && mvn compile` (no test change needed)
**Commit:** `feat(websocket): add sendPaused event for token exhaustion`

---

### Task 2.4: AgentController — new resume + runs endpoints
**File:** `backend/src/main/java/com/agent/orchestrator/controller/AgentController.java` (MODIFY)
**Test:** `backend/src/test/java/com/agent/orchestrator/controller/ExecutionResilienceControllerTest.java` (NEW)
**Depends:** 2.1, 2.2 (needs SchemaService resume methods)

**Add new endpoints:**

```java
// ── Execution Resilience (persisted runs) ──

@GetMapping("/schemas/{id}/runs")
public List<ExecutionRun> getExecutionRuns(@PathVariable String id) {
    return schemaService.findExecutionRuns(id);
}

@GetMapping("/schemas/{id}/runs/paused")
public ResponseEntity<ExecutionRun> getPausedRun(@PathVariable String id) {
    ExecutionRun paused = schemaService.getPausedRun(id);
    if (paused == null) {
        return ResponseEntity.noContent().build();
    }
    // Also attach node executions
    return ResponseEntity.ok(paused);
}

@GetMapping("/schemas/{id}/runs/{runId}/nodes")
public List<NodeExecution> getRunNodeExecutions(@PathVariable String id,
                                                  @PathVariable String runId) {
    return schemaService.findNodeExecutions(runId);
}

@PostMapping("/schemas/{id}/resume")
public Map<String, String> resumeExecution(@PathVariable String id) {
    schemaService.resumeExecution(id);
    return Map.of("status", "resumed", "schemaId", id);
}
```

**Add test file:**

```java
package com.agent.orchestrator.controller;

import com.agent.orchestrator.model.ExecutionRun;
import com.agent.orchestrator.model.NodeExecution;
import com.agent.orchestrator.service.SchemaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionResilienceControllerTest {

    @Mock SchemaService schemaService;

    @InjectMocks
    AgentController controller;

    @Test
    void getExecutionRuns_delegatesToService() {
        String schemaId = "schema-1";
        ExecutionRun run = new ExecutionRun();
        run.setId(UUID.randomUUID().toString());
        run.setSchemaId(schemaId);
        run.setStatus("completed");

        when(schemaService.findExecutionRuns(schemaId)).thenReturn(List.of(run));

        List<ExecutionRun> result = controller.getExecutionRuns(schemaId);
        assertEquals(1, result.size());
        assertEquals("completed", result.get(0).getStatus());
    }

    @Test
    void getPausedRun_returnsNoContentWhenNone() {
        when(schemaService.getPausedRun("schema-1")).thenReturn(null);

        ResponseEntity<ExecutionRun> response = controller.getPausedRun("schema-1");
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNull(response.getBody()); // noContent
    }

    @Test
    void resumeExecution_delegatesToService() {
        String schemaId = "schema-1";
        Map<String, String> result = controller.resumeExecution(schemaId);
        assertEquals("resumed", result.get("status"));
        verify(schemaService).resumeExecution(schemaId);
    }
}
```

**Verify:** `cd backend && mvn test -Dtest=ExecutionResilienceControllerTest`
**Commit:** `feat(controller): add runs + resume REST endpoints`

---

## Batch 3: Frontend (parallel — 4 implementers)

All tasks in this batch depend on Batch 2 completing (need backend endpoints).

### Task 3.1: API layer additions (runs + resume endpoints)
**File:** `frontend/src/services/api.ts` (MODIFY)
**Test:** none (tested via component integration)
**Depends:** 2.4 (needs the endpoints)

**Add to `schemaApi` object:**

```typescript
// Types for execution resilience
export interface ExecutionRunResponse {
  id: string;
  schemaId: string;
  status: string;
  mode: string;
  totalTokens: number;
  estimatedCost: number;
  error: string | null;
  resumesFrom: string | null;
  startedAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface NodeExecutionResponse {
  id: string;
  runId: string;
  nodeId: string;
  nodeName: string;
  nodeType: string;
  status: string;
  tokensUsed: number;
  durationMs: number;
  toolCalls: number;
  error: string | null;
  outputSummary: string | null;
  filesWritten: string | null;
  configHash: string;
  startedAt: string;
  completedAt: string | null;
}

// Add inside schemaApi:
  /** Получить все запуски для схемы */
  async getRuns(schemaId: string): Promise<ExecutionRunResponse[]> {
    const response = await api.get(`/schemas/${schemaId}/runs`);
    return response.data;
  },

  /** Получить приостановленный запуск */
  async getPausedRun(schemaId: string): Promise<ExecutionRunResponse | null> {
    const response = await api.get(`/schemas/${schemaId}/runs/paused`);
    if (response.status === 204) return null;
    return response.data;
  },

  /** Получить результаты узлов для запуска */
  async getRunNodes(runId: string, schemaId: string): Promise<NodeExecutionResponse[]> {
    const response = await api.get(`/schemas/${schemaId}/runs/${runId}/nodes`);
    return response.data;
  },

  /** Возобновить выполнение схемы */
  async resumeSchema(schemaId: string): Promise<void> {
    await api.post(`/schemas/${schemaId}/resume`);
  },
```

**Commit:** `feat(api): add runs/resume API methods`

---

### Task 3.2: ResumeBanner.vue — new component
**File:** `frontend/src/components/studio/ResumeBanner.vue`
**Test:** `frontend/src/components/studio/__tests__/ResumeBanner.test.ts`
**Depends:** 3.1 (uses API types)

Complete component with template for "Resume paused execution?" banner:

```vue
<script setup lang="ts">
import { ref, computed } from 'vue'
import { schemaApi, type ExecutionRunResponse } from '@/services/api'

const props = defineProps<{
  schemaId: string
}>()

const emit = defineEmits<{
  (e: 'resume'): void
  (e: 'start-fresh'): void
  (e: 'dismiss'): void
}>()

const pausedRun = ref<ExecutionRunResponse | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const dismissed = ref(false)

const visible = computed(() => !dismissed.value && pausedRun.value !== null && !loading.value)

onMounted(async () => {
  try {
    pausedRun.value = await schemaApi.getPausedRun(props.schemaId)
  } catch (err) {
    error.value = (err as Error).message
  } finally {
    loading.value = false
  }
})

function handleResume() {
  emit('resume')
  dismissed.value = true
}

function handleStartFresh() {
  emit('start-fresh')
  dismissed.value = true
}

function handleDismiss() {
  dismissed.value = true
  emit('dismiss')
}
</script>

<template>
  <Transition name="slide-down">
    <div v-if="visible" class="resume-banner">
      <div class="banner-icon">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="20" height="20">
          <circle cx="12" cy="12" r="10" />
          <polyline points="12 6 12 12 16 14" />
        </svg>
      </div>
      <div class="banner-content">
        <span class="banner-title">Execution paused</span>
        <span class="banner-subtitle">A paused execution was found — you can resume or start fresh</span>
      </div>
      <div class="banner-actions">
        <button class="btn btn-primary btn-sm" @click="handleResume">
          Resume
        </button>
        <button class="btn btn-secondary btn-sm" @click="handleStartFresh">
          Start Fresh
        </button>
        <button class="btn btn-ghost btn-sm" @click="handleDismiss">
          Dismiss
        </button>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.resume-banner {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem 1rem;
  background: var(--bg-warning, #fff8e1);
  border-bottom: 1px solid var(--border-warning, #ffe082);
  font-size: 0.875rem;
}

.banner-icon {
  flex-shrink: 0;
  color: var(--text-warning, #f57c00);
  display: flex;
}

.banner-content {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.banner-title {
  font-weight: 600;
  color: var(--text-primary);
}

.banner-subtitle {
  font-size: 0.8rem;
  color: var(--text-secondary);
}

.banner-actions {
  display: flex;
  gap: 0.5rem;
  flex-shrink: 0;
}

.btn { border: none; border-radius: 6px; cursor: pointer; padding: 0.4rem 0.75rem; font-size: 0.8rem; font-weight: 500; }
.btn-primary { background: var(--accent, #6c63ff); color: white; }
.btn-primary:hover { opacity: 0.9; }
.btn-secondary { background: var(--bg-secondary, #e8e8e8); color: var(--text-primary); }
.btn-secondary:hover { background: var(--bg-hover, #ddd); }
.btn-ghost { background: transparent; color: var(--text-secondary); }
.btn-ghost:hover { background: var(--bg-hover, #f0f0f0); }
.btn-sm { padding: 0.3rem 0.6rem; font-size: 0.8rem; }

.slide-down-enter-active, .slide-down-leave-active { transition: all 0.3s ease; }
.slide-down-enter-from, .slide-down-leave-to { opacity: 0; transform: translateY(-100%); }
</style>
```

**Test:**

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ResumeBanner from '../ResumeBanner.vue'

// Mock the api module
vi.mock('@/services/api', () => ({
  schemaApi: {
    getPausedRun: vi.fn(),
  },
}))

import { schemaApi } from '@/services/api'

describe('ResumeBanner', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows banner when paused run exists', async () => {
    ;(schemaApi.getPausedRun as ReturnType<typeof vi.fn>).mockResolvedValue({
      id: 'run-1',
      status: 'paused',
      completedNodes: 3,
    })

    const wrapper = mount(ResumeBanner, { props: { schemaId: 'schema-1' } })
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('Execution paused')
  })

  it('hides banner when no paused run', async () => {
    ;(schemaApi.getPausedRun as ReturnType<typeof vi.fn>).mockResolvedValue(null)

    const wrapper = mount(ResumeBanner, { props: { schemaId: 'schema-1' } })
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.resume-banner').exists()).toBe(false)
  })

  it('emits resume when resume button clicked', async () => {
    ;(schemaApi.getPausedRun as ReturnType<typeof vi.fn>).mockResolvedValue({
      id: 'run-1',
      status: 'paused',
    })

    const wrapper = mount(ResumeBanner, { props: { schemaId: 'schema-1' } })
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    const resumeBtn = wrapper.find('.btn-primary')
    await resumeBtn.trigger('click')
    expect(wrapper.emitted('resume')).toBeTruthy()
  })

  it('emits start-fresh when start fresh clicked', async () => {
    ;(schemaApi.getPausedRun as ReturnType<typeof vi.fn>).mockResolvedValue({
      id: 'run-1',
      status: 'paused',
    })

    const wrapper = mount(ResumeBanner, { props: { schemaId: 'schema-1' } })
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    const freshBtn = wrapper.findAll('button')[1]
    await freshBtn.trigger('click')
    expect(wrapper.emitted('start-fresh')).toBeTruthy()
  })

  it('dismisses when dismiss clicked', async () => {
    ;(schemaApi.getPausedRun as ReturnType<typeof vi.fn>).mockResolvedValue({
      id: 'run-1',
      status: 'paused',
    })

    const wrapper = mount(ResumeBanner, { props: { schemaId: 'schema-1' } })
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    const dismissBtn = wrapper.findAll('button')[2]
    await dismissBtn.trigger('click')
    expect(wrapper.emitted('dismiss')).toBeTruthy()
    expect(wrapper.find('.resume-banner').exists()).toBe(false)
  })
})
```

**Verify:** `cd frontend && npm run test:unit -- -t ResumeBanner`
**Commit:** `feat(frontend): add ResumeBanner component for paused execution recovery`

---

### Task 3.3: TimelineView enhancement — show persisted runs
**File:** `frontend/src/components/studio/TimelineView.vue` (MODIFY)
**Test:** `frontend/src/components/studio/__tests__/TimelineView.test.ts` (MODIFY)
**Depends:** 3.1 (needs API types)

**Integration approach:** Add a new section above the existing timeline that lists all persisted runs. Each run shows date, status badge, token/duration summary. Expandable per-node shows green=completed, yellow=skipped, red=failed, grey=pending. Visual separator between chained runs (child runs from resume).

**Changes:**

```vue
<script setup lang="ts">
// ... existing imports ...
import { schemaApi, type ExecutionRunResponse, type NodeExecutionResponse } from '@/services/api'

const props = defineProps<{
  schemaId?: string  // NEW: optional schemaId for loading persisted runs
}>()

// Add:
const persistedRuns = ref<ExecutionRunResponse[]>([])
const expandedRunId = ref<string | null>(null)
const runNodes = ref<Map<string, NodeExecutionResponse[]>>(new Map())
const loadingRuns = ref(false)

// Load persisted runs
onMounted(async () => {
  if (props.schemaId) {
    await loadPersistedRuns()
  }
})

async function loadPersistedRuns() {
  loadingRuns.value = true
  try {
    persistedRuns.value = await schemaApi.getRuns(props.schemaId!)
  } catch (e) {
    console.error('Failed to load runs:', e)
  } finally {
    loadingRuns.value = false
  }
}

async function toggleRunDetails(runId: string) {
  if (expandedRunId.value === runId) {
    expandedRunId.value = null
    return
  }
  expandedRunId.value = runId
  if (!runNodes.value.has(runId)) {
    try {
      const nodes = await schemaApi.getRunNodes(runId, props.schemaId!)
      runNodes.value.set(runId, nodes)
    } catch (e) {
      console.error('Failed to load run nodes:', e)
    }
  }
}

function getNodeStatusColor(status: string): string {
  switch (status) {
    case 'completed': return '#4caf50'
    case 'skipped': return '#ffc107'
    case 'failed': return '#f44336'
    case 'running': return '#2196f3'
    default: return '#9e9e9e'
  }
}

function formatDuration(durationMs: number): string {
  if (durationMs < 1000) return durationMs + 'ms'
  return (durationMs / 1000).toFixed(1) + 's'
}
</script>

<template>
  <div class="timeline-view">
    <div class="timeline-header">
      <h2>Execution Timeline</h2>
      <div class="header-info">
        <span v-if="loadingRuns" class="loading-spinner" />
        <span class="event-count">{{ events.length }} live + {{ persistedRuns.length }} runs</span>
      </div>
    </div>

    <!-- Persisted runs section -->
    <div v-if="persistedRuns.length > 0" class="persisted-runs">
      <div class="section-label">Saved Runs</div>
      <div
        v-for="run in persistedRuns"
        :key="run.id"
        class="run-card"
        :class="{ expanded: expandedRunId === run.id }"
        @click="toggleRunDetails(run.id)"
      >
        <div class="run-header">
          <span class="run-status" :class="run.status">{{ run.status }}</span>
          <span class="run-date">{{ new Date(run.startedAt).toLocaleDateString() }} {{ new Date(run.startedAt).toLocaleTimeString() }}</span>
          <span v-if="run.resumesFrom" class="run-resume-badge">↳ resumed</span>
          <span class="run-summary" v-if="run.completedAt">
            {{ formatDuration(new Date(run.completedAt).getTime() - new Date(run.startedAt).getTime()) }}
          </span>
        </div>

        <Transition name="expand">
          <div v-if="expandedRunId === run.id" class="run-details">
            <div v-if="runNodes.get(run.id)?.length" class="node-list">
              <div
                v-for="ne in runNodes.get(run.id)!"
                :key="ne.id"
                class="node-execution-row"
              >
                <span class="node-status-dot" :style="{ background: getNodeStatusColor(ne.status) }" />
                <span class="node-name">{{ ne.nodeName || ne.nodeId }}</span>
                <span class="node-type">{{ ne.nodeType }}</span>
                <span class="node-meta" v-if="ne.durationMs">{{ formatDuration(ne.durationMs) }}</span>
                <span class="node-meta" v-if="ne.tokensUsed">{{ ne.tokensUsed }} tokens</span>
                <span v-if="ne.status === 'skipped'" class="node-cached">cached</span>
              </div>
            </div>
            <div v-else class="node-list-empty">Loading...</div>
          </div>
        </Transition>
      </div>
    </div>

    <div v-if="persistedRuns.length > 0" class="runs-divider">
      <span>Live Events</span>
    </div>

    <!-- Existing live events timeline -->
    <div v-if="events.length === 0" class="timeline-empty">
      <!-- empty state unchanged -->
    </div>
    <div v-else class="timeline-list">
      <!-- existing timeline entries unchanged -->
    </div>
  </div>
</template>
```

**Key CSS additions needed in `<style>` (add to existing):**

```css
.persisted-runs {
  padding: 0.75rem 1rem;
  border-bottom: 1px solid var(--border-color);
}

.section-label {
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
  color: var(--text-muted);
  margin-bottom: 0.5rem;
}

.run-card {
  background: var(--bg-secondary);
  border-radius: 8px;
  margin-bottom: 0.5rem;
  cursor: pointer;
  transition: box-shadow 0.2s;
}
.run-card:hover { box-shadow: 0 1px 4px rgba(0,0,0,0.1); }
.run-card.expanded { box-shadow: 0 2px 8px rgba(0,0,0,0.12); }

.run-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.6rem 0.75rem;
  font-size: 0.85rem;
}

.run-status {
  padding: 0.15rem 0.4rem;
  border-radius: 4px;
  font-size: 0.7rem;
  font-weight: 600;
  text-transform: uppercase;
}
.run-status.completed { background: #e8f5e9; color: #2e7d32; }
.run-status.paused { background: #fff8e1; color: #f57c00; }
.run-status.failed { background: #ffebee; color: #c62828; }
.run-status.running { background: #e3f2fd; color: #1565c0; }
.run-status.cancelled { background: #f5f5f5; color: #757575; }

.run-date { color: var(--text-secondary); font-size: 0.8rem; }
.run-resume-badge { color: var(--accent); font-size: 0.75rem; font-weight: 500; }
.run-summary { margin-left: auto; color: var(--text-secondary); font-size: 0.8rem; }

.run-details { border-top: 1px solid var(--border-color); padding: 0.5rem 0.75rem; }
.node-list { display: flex; flex-direction: column; gap: 0.3rem; }
.node-execution-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.3rem 0;
  font-size: 0.8rem;
}
.node-status-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.node-name { font-weight: 500; flex: 1; }
.node-type { color: var(--text-muted); font-size: 0.75rem; }
.node-meta { color: var(--text-secondary); font-size: 0.75rem; }
.node-cached { color: #f57c00; font-size: 0.7rem; font-weight: 500; }
.node-list-empty { color: var(--text-muted); font-size: 0.8rem; padding: 0.5rem; }

.runs-divider {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.5rem 1rem;
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
  color: var(--text-muted);
}
.runs-divider::before, .runs-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--border-color);
}

.expand-enter-active, .expand-leave-active { transition: all 0.2s ease; overflow: hidden; }
.expand-enter-from, .expand-leave-to { opacity: 0; max-height: 0; }
```

Additionally, add to the `<script>` imports at the top:
```typescript
import { onMounted } from 'vue'
```

**Commit:** `feat(frontend): enhance TimelineView with persisted run history`

---

### Task 3.4: StudioView modifications — resume flow integration
**File:** `frontend/src/views/StudioView.vue` (MODIFY)
**Test:** none (functional/visual — manual verification)
**Depends:** 3.2 (needs ResumeBanner), 2.1 (needs resume endpoint)

**Changes:**

1. Import and add ResumeBanner above the studio-content area
2. Add `onResume` handler that calls schemaApi.resumeSchema + connects WebSocket
3. Expand the "Run" button to support resume
4. Handle new `paused` WebSocket event

```vue
<script setup lang="ts">
// Add imports:
import ResumeBanner from '@/components/studio/ResumeBanner.vue'

// Add state:
const hasPausedRun = ref(false)

// Add handler:
async function onResume() {
  isRunning.value = true
  executionError.value = null
  nodeResults.value = {}
  nodeStatuses.value = {}
  executionProgress.value = null

  connect(appId.value, {
    // ... same callbacks as startExecution ...
    onProgress: (data) => { /* same */ },
    onResult: (data) => { /* same */ },
    onComplete: () => { /* same */ },
    onError: (data) => { /* same */ },
    onPaused: (data) => {  // NEW
      isRunning.value = false
      executionError.value = `Execution paused: ${data.error}`
      addStepEvent('__execution__', 'paused', data.error)
    },
  })

  try {
    await schemaApi.resumeSchema(appId.value)
  } catch (err) {
    executionError.value = (err as Error).message
    isRunning.value = false
    disconnect()
  }
}

async function onStartFresh() {
  // Clear paused state and do a fresh execution
  hasPausedRun.value = false
  startExecution(false)
}
</script>

<template>
  <div class="studio">
    <StudioTopBar ... />
    
    <!-- Resume banner -->
    <ResumeBanner
      :schema-id="appId"
      @resume="onResume"
      @start-fresh="onStartFresh"
      @dismiss="hasPausedRun = false"
    />
    
    <!-- Existing content -->
    <div class="studio-content">
      ...
    </div>
  </div>
</template>
```

**Also update WebSocket callback in `startExecution` to add `onPaused`:**
```typescript
connect(appId.value, {
  // ... existing callbacks ...
  onPaused: (data) => {
    isRunning.value = false
    executionError.value = `Execution paused: ${data.error}`
    addStepEvent('__execution__', 'paused', data.error)
  },
})
```

**Commit:** `feat(frontend): integrate resume flow in StudioView`

---

## Batch 4: Integration Tests (parallel — 3 implementers)

### Task 4.1: Full resume flow integration test
**File:** `backend/src/test/java/com/agent/orchestrator/service/ExecutionResilienceFlowIntegrationTest.java`
**Depends:** 2.1, 2.2, 2.4 (all backend changes complete)

```java
package com.agent.orchestrator.service;

import com.agent.orchestrator.config.DbConfig;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.model.*;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionResilienceFlowIntegrationTest {

    @TempDir
    Path tempDir;

    private ExecutionRepository executionRepository;
    private SchemaService schemaService;

    @Mock Neo4jSchemaRepository schemaRepository;
    @Mock ExecutionWebSocketHandler webSocketHandler;
    @Mock MemPalaceClient memPalaceClient;
    @Mock SettingsService settingsService;
    @Mock MetricsService metricsService;
    @Mock NodeExecutor nodeExecutor;
    @Mock SchemaExporter schemaExporter;
    @Mock LlmService llmService;

    @BeforeEach
    void setUp() {
        String dbPath = tempDir.resolve("flow-test.db").toString();
        DbConfig config = new DbConfig(dbPath);
        executionRepository = new ExecutionRepository(config);
        PlanService planService = mock(PlanService.class);

        schemaService = new SchemaService(schemaRepository, webSocketHandler, memPalaceClient,
                settingsService, metricsService, nodeExecutor, schemaExporter, llmService,
                planService, executionRepository);
    }

    @Test
    void resumeFlow_createChildRunWithSkippedNodes() throws Exception {
        // 1. Create a schema with 3 nodes
        String schemaId = "resume-flow-schema";
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId(schemaId);
        schema.setName("Resume Test");

        Node n1 = new Node(); n1.setId("n1"); n1.setName("Source"); n1.setType("source");
        Node n2 = new Node(); n2.setId("n2"); n2.setName("Agent"); n2.setType("agent");
        Node n3 = new Node(); n3.setId("n3"); n3.setName("Output"); n3.setType("output");

        Node.NodeData data2 = new Node.NodeData();
        data2.setModel("gpt-4o");
        data2.setUserPrompt("Analyze");
        n2.setData(data2);

        // Make n1 depend on nothing, n2 on n1, n3 on n2
        schema.setNodes(List.of(n1, n2, n3));
        Edge e1 = new Edge(); e1.setId("e1"); e1.setSource("n1"); e1.setTarget("n2");
        Edge e2 = new Edge(); e2.setId("e2"); e2.setSource("n2"); e2.setTarget("n3");
        schema.setEdges(List.of(e1, e2));

        when(schemaRepository.findById(schemaId)).thenReturn(schema);

        // 2. Simulate a paused run where n1 completed, n2 failed
        String runId = UUID.randomUUID().toString();
        ExecutionRun pausedRun = new ExecutionRun();
        pausedRun.setId(runId);
        pausedRun.setSchemaId(schemaId);
        pausedRun.setStatus("paused");
        pausedRun.setMode("EXECUTE");
        pausedRun.setStartedAt(java.time.Instant.now().toString());
        pausedRun.setError("Token limit");
        executionRepository.createRun(pausedRun);

        // N1 completed
        String hash1 = schemaService.computeConfigHash(n1, schema);
        NodeExecution ne1 = new NodeExecution();
        ne1.setId(UUID.randomUUID().toString());
        ne1.setRunId(runId);
        ne1.setNodeId("n1");
        ne1.setNodeName("Source");
        ne1.setNodeType("source");
        ne1.setStatus("completed");
        ne1.setConfigHash(hash1);
        ne1.setOutputSummary("{\"data\": \"source data\"}");
        ne1.setStartedAt(java.time.Instant.now().toString());
        ne1.setCompletedAt(java.time.Instant.now().toString());
        executionRepository.createNodeExecution(ne1);

        // N2 failed (token exhaustion)
        String hash2 = schemaService.computeConfigHash(n2, schema);
        NodeExecution ne2 = new NodeExecution();
        ne2.setId(UUID.randomUUID().toString());
        ne2.setRunId(runId);
        ne2.setNodeId("n2");
        ne2.setNodeName("Agent");
        ne2.setNodeType("agent");
        ne2.setStatus("failed");
        ne2.setConfigHash(hash2);
        ne2.setError("HTTP 429 rate limit");
        ne2.setStartedAt(java.time.Instant.now().toString());
        executionRepository.createNodeExecution(ne2);

        // N3 never started (depends on n2)
        NodeExecution ne3 = new NodeExecution();
        ne3.setId(UUID.randomUUID().toString());
        ne3.setRunId(runId);
        ne3.setNodeId("n3");
        ne3.setNodeName("Output");
        ne3.setNodeType("output");
        ne3.setStatus("pending");
        ne3.setConfigHash(hash2);
        ne3.setStartedAt(java.time.Instant.now().toString());
        executionRepository.createNodeExecution(ne3);

        // 3. Call resumeExecution
        when(nodeExecutor.getNodeResults()).thenReturn(new ConcurrentHashMap<>());
        schemaService.resumeExecution(schemaId);

        // 4. Verify: new child run created
        List<ExecutionRun> runs = executionRepository.getRunsBySchema(schemaId);
        assertEquals(2, runs.size(), "Should have parent + child run");

        ExecutionRun childRun = runs.stream()
                .filter(r -> r.getResumesFrom() != null)
                .findFirst().orElse(null);
        assertNotNull(childRun, "Child run should reference parent");
        assertEquals(runId, childRun.getResumesFrom());
        assertEquals("running", childRun.getStatus());

        // 5. Verify: N1 is SKIPPED (same config hash), N2 is PENDING (was failed), N3 is PENDING
        List<NodeExecution> childNodes = executionRepository.getNodeExecutionsByRun(childRun.getId());
        assertEquals(3, childNodes.size());

        NodeExecution childN1 = childNodes.stream().filter(n -> "n1".equals(n.getNodeId())).findFirst().orElse(null);
        NodeExecution childN2 = childNodes.stream().filter(n -> "n2".equals(n.getNodeId())).findFirst().orElse(null);
        NodeExecution childN3 = childNodes.stream().filter(n -> "n3".equals(n.getNodeId())).findFirst().orElse(null);

        assertNotNull(childN1);
        assertEquals("skipped", childN1.getStatus(), "N1 should be skipped (same config)");

        assertNotNull(childN2);
        assertEquals("pending", childN2.getStatus(), "N2 should be pending (was failed)");

        assertNotNull(childN3);
        assertEquals("pending", childN3.getStatus(), "N3 should be pending (depends on failed N2)");
    }

    @Test
    void resumeFlow_configChanged_reExecutesNode() {
        String schemaId = "resume-config-change";
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId(schemaId);
        schema.setName("Config Change Test");

        Node n1 = new Node(); n1.setId("n1"); n1.setName("Agent"); n1.setType("agent");
        Node.NodeData data1 = new Node.NodeData();
        data1.setModel("claude-3");
        data1.setUserPrompt("Old prompt");
        n1.setData(data1);
        schema.setNodes(List.of(n1));
        schema.setEdges(List.of());

        when(schemaRepository.findById(schemaId)).thenReturn(schema);

        // Paused run with old config
        String runId = UUID.randomUUID().toString();
        ExecutionRun pausedRun = new ExecutionRun();
        pausedRun.setId(runId);
        pausedRun.setSchemaId(schemaId);
        pausedRun.setStatus("paused");
        pausedRun.setMode("EXECUTE");
        pausedRun.setStartedAt(java.time.Instant.now().toString());
        pausedRun.setError("Rate limit");
        executionRepository.createRun(pausedRun);

        // Old hash (different prompt)
        String oldHash = "old-hash-different-config";
        NodeExecution ne1 = new NodeExecution();
        ne1.setId(UUID.randomUUID().toString());
        ne1.setRunId(runId);
        ne1.setNodeId("n1");
        ne1.setNodeName("Agent");
        ne1.setNodeType("agent");
        ne1.setStatus("completed");
        ne1.setConfigHash(oldHash);
        ne1.setOutputSummary("Old result");
        ne1.setStartedAt(java.time.Instant.now().toString());
        ne1.setCompletedAt(java.time.Instant.now().toString());
        executionRepository.createNodeExecution(ne1);

        // Resume
        when(nodeExecutor.getNodeResults()).thenReturn(new ConcurrentHashMap<>());
        schemaService.resumeExecution(schemaId);

        List<ExecutionRun> runs = executionRepository.getRunsBySchema(schemaId);
        assertEquals(2, runs.size());

        ExecutionRun childRun = runs.stream()
                .filter(r -> r.getResumesFrom() != null)
                .findFirst().orElse(null);
        assertNotNull(childRun);

        List<NodeExecution> childNodes = executionRepository.getNodeExecutionsByRun(childRun.getId());
        NodeExecution childN1 = childNodes.stream().filter(n -> "n1".equals(n.getNodeId())).findFirst().orElse(null);
        assertNotNull(childN1);

        // Config hash differs → should NOT be skipped
        assertNotEquals("skipped", childN1.getStatus(),
                "Node with changed config should NOT be skipped");
    }
}
```

**Verify:** `cd backend && mvn test -Dtest=ExecutionResilienceFlowIntegrationTest`
**Commit:** `test(integration): add full resume flow integration test`

---

### Task 4.2: Frontend TimelineView run display test
**File:** `frontend/src/components/studio/__tests__/TimelineView.test.ts` (NEW)
**Depends:** 3.3 (TimelineView changes)

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import TimelineView from '../TimelineView.vue'

vi.mock('@/services/api', () => ({
  schemaApi: {
    getRuns: vi.fn(),
    getRunNodes: vi.fn(),
  },
}))

vi.mock('@/composables/useExecutionState', () => ({
  useExecutionState: () => ({
    stepEvents: { value: [] },
  }),
}))

import { schemaApi } from '@/services/api'

describe('TimelineView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders persisted runs section', async () => {
    ;(schemaApi.getRuns as ReturnType<typeof vi.fn>).mockResolvedValue([
      { id: 'run-1', schemaId: 's1', status: 'completed', mode: 'EXECUTE',
        startedAt: '2026-05-15T10:00:00Z', completedAt: '2026-05-15T10:05:00Z',
        totalTokens: 500, estimatedCost: 0.01, error: null, resumesFrom: null,
        updatedAt: '2026-05-15T10:05:00Z' },
    ])
    ;(schemaApi.getRunNodes as ReturnType<typeof vi.fn>).mockResolvedValue([
      { id: 'ne-1', runId: 'run-1', nodeId: 'n1', nodeName: 'Source',
        nodeType: 'source', status: 'completed', tokensUsed: 100, durationMs: 500,
        toolCalls: 0, error: null, outputSummary: null, filesWritten: null,
        configHash: 'abc', startedAt: '2026-05-15T10:00:00Z', completedAt: '2026-05-15T10:01:00Z' },
    ])

    const wrapper = mount(TimelineView, { props: { schemaId: 's1' } })
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('Saved Runs')
    expect(wrapper.text()).toContain('completed')
    expect(wrapper.text()).toContain('5/15/2026')
  })

  it('shows empty state when no runs', async () => {
    ;(schemaApi.getRuns as ReturnType<typeof vi.fn>).mockResolvedValue([])

    const wrapper = mount(TimelineView, { props: { schemaId: 's1' } })
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    // Should show the existing empty state for live events
    expect(wrapper.text()).toContain('Run your app')
  })

  it('toggles run detail on click', async () => {
    ;(schemaApi.getRuns as ReturnType<typeof vi.fn>).mockResolvedValue([
      { id: 'run-1', schemaId: 's1', status: 'completed', mode: 'EXECUTE',
        startedAt: '2026-05-15T10:00:00Z', completedAt: '2026-05-15T10:05:00Z',
        totalTokens: 500, estimatedCost: 0.01, error: null, resumesFrom: null,
        updatedAt: '2026-05-15T10:05:00Z' },
    ])
    ;(schemaApi.getRunNodes as ReturnType<typeof vi.fn>).mockResolvedValue([
      { id: 'ne-1', runId: 'run-1', nodeId: 'n1', nodeName: 'Agent',
        nodeType: 'agent', status: 'completed', tokensUsed: 500, durationMs: 3000,
        toolCalls: 5, error: null, outputSummary: 'result', filesWritten: null,
        configHash: 'abc', startedAt: '2026-05-15T10:00:00Z', completedAt: '2026-05-15T10:01:00Z' },
    ])

    const wrapper = mount(TimelineView, { props: { schemaId: 's1' } })
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    // Click on run card to expand
    const runCard = wrapper.find('.run-card')
    await runCard.trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('Agent')
    expect(wrapper.text()).toContain('3s')
  })
})
```

**Verify:** `cd frontend && npm run test:unit -- -t "TimelineView"`
**Commit:** `test(frontend): add TimelineView run display tests`

---

## Implementation Order

| Batch | Task | File | Implementer |
|-------|------|------|-------------|
| 1 | 1.1 | ExecutionRun.java | BE-1 |
| 1 | 1.2 | NodeExecution.java | BE-1 |
| 1 | 1.3 | ExecutionCheckpoint.java | BE-1 |
| 1 | 1.4 | ExecutionRepository.java + Test | BE-2 |
| 2 | 2.1 | SchemaService.java (MODIFY) + Test | BE-3 |
| 2 | 2.2 | NodeExecutor.java (MODIFY) + Test | BE-3 |
| 2 | 2.3 | ExecutionWebSocketHandler.java (MODIFY) | BE-2 |
| 2 | 2.4 | AgentController.java (MODIFY) + Test | BE-2 |
| 3 | 3.1 | api.ts (MODIFY) | FE-1 |
| 3 | 3.2 | ResumeBanner.vue + Test | FE-2 |
| 3 | 3.3 | TimelineView.vue (MODIFY) + Test | FE-1 |
| 3 | 3.4 | StudioView.vue (MODIFY) | FE-2 |
| 4 | 4.1 | ExecutionResilienceFlowIntegrationTest.java | BE-3 |
| 4 | 4.2 | TimelineView.test.ts | FE-1 |

## Verification Commands

```bash
# Batch 1
cd backend && mvn test -Dtest=ExecutionRepositoryTest

# Batch 2
cd backend && mvn test -Dtest=SchemaServiceResilienceTest
cd backend && mvn test -Dtest=NodeExecutorResilienceTest
cd backend && mvn test -Dtest=ExecutionResilienceControllerTest

# Batch 3
cd frontend && npm run test:unit -- -t ResumeBanner
cd frontend && npm run test:unit -- -t TimelineView

# Batch 4 (all integration)
cd backend && mvn test -Dtest=ExecutionResilienceFlowIntegrationTest
cd frontend && npm run test:unit -- -t "TimelineView"

# Full verification
cd backend && mvn test
cd frontend && npm run test:unit
```
