package com.agent.orchestrator.repository;

import com.agent.orchestrator.config.DbConfig;
import com.agent.orchestrator.model.Plan;
import com.agent.orchestrator.model.PlanLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Repository
public class PlanRepository {

    private static final Logger log = LoggerFactory.getLogger(PlanRepository.class);
    private final String dbUrl;
    private final ObjectMapper mapper;

    public PlanRepository(DbConfig dbConfig) {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        this.dbUrl = dbConfig.getDbUrl();
        createTable();
    }

    private void createTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS plans (
                id TEXT PRIMARY KEY,
                workspace_id TEXT NOT NULL,
                name TEXT NOT NULL,
                parent_id TEXT,
                schema_id TEXT,
                level TEXT DEFAULT 'PROJECT',
                tasks_json TEXT NOT NULL,
                created_at TEXT,
                updated_at TEXT
            )
            """;
        String migrateSql = """
            ALTER TABLE plans ADD COLUMN parent_id TEXT
            """;

        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            try { stmt.execute(migrateSql); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE plans ADD COLUMN schema_id TEXT"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE plans ADD COLUMN level TEXT DEFAULT 'PROJECT'"); } catch (SQLException ignored) {}
            log.info("Таблица plans создана/проверена");
        } catch (SQLException e) {
            log.error("Ошибка создания таблицы plans: {}", e.getMessage());
        }
    }

    public void save(Plan plan) {
        String sql = """
            INSERT INTO plans (id, workspace_id, name, parent_id, schema_id, level, tasks_json, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                workspace_id = excluded.workspace_id,
                name = excluded.name,
                parent_id = excluded.parent_id,
                schema_id = excluded.schema_id,
                level = excluded.level,
                tasks_json = excluded.tasks_json,
                updated_at = excluded.updated_at
            """;

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, plan.getId());
            pstmt.setString(2, plan.getWorkspaceId());
            pstmt.setString(3, plan.getName());
            pstmt.setString(4, plan.getParentId());
            pstmt.setString(5, plan.getSchemaId());
            pstmt.setString(6, plan.getLevel() != null ? plan.getLevel().name() : "PROJECT");
            pstmt.setString(7, mapper.writeValueAsString(plan.getTasks()));
            pstmt.setString(8, plan.getCreatedAt() != null ? plan.getCreatedAt().toString() : null);
            pstmt.setString(9, plan.getUpdatedAt() != null ? plan.getUpdatedAt().toString() : null);
            pstmt.executeUpdate();

        } catch (Exception e) {
            throw new RuntimeException("Ошибка сохранения плана: " + e.getMessage(), e);
        }
    }

    public Plan findByWorkspaceId(String workspaceId) {
        String sql = "SELECT * FROM plans WHERE workspace_id = ? LIMIT 1";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, workspaceId);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                return mapRow(rs);
            }

        } catch (Exception e) {
            log.error("Ошибка чтения плана: {}", e.getMessage());
            e.printStackTrace();
        }

        return null;
    }

    public List<Plan> findAll(String workspaceId) {
        List<Plan> plans = new ArrayList<>();
        String sql = "SELECT * FROM plans WHERE workspace_id = ?";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, workspaceId);
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                plans.add(mapRow(rs));
            }

        } catch (Exception e) {
            log.error("Ошибка чтения планов: {}", e.getMessage());
        }

        return plans;
    }

    public List<String> findAllWorkspaceIds() {
        List<String> ids = new ArrayList<>();
        String sql = "SELECT DISTINCT workspace_id FROM plans ORDER BY workspace_id";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                ids.add(rs.getString("workspace_id"));
            }
        } catch (Exception e) {
            log.error("Ошибка чтения workspaces: {}", e.getMessage());
        }

        return ids;
    }

    public void delete(String id) {
        String sql = "DELETE FROM plans WHERE id = ?";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, id);
            pstmt.executeUpdate();

        } catch (SQLException e) {
            throw new RuntimeException("Ошибка удаления плана: " + e.getMessage(), e);
        }
    }

    public List<Plan> findByParentId(String parentId) {
        List<Plan> plans = new ArrayList<>();
        String sql = "SELECT * FROM plans WHERE parent_id = ? ORDER BY created_at";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, parentId);
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                plans.add(mapRow(rs));
            }

        } catch (Exception e) {
            log.error("Ошибка чтения дочерних планов: {}", e.getMessage());
        }

        return plans;
    }

    public Plan findBySchemaId(String schemaId) {
        String sql = "SELECT * FROM plans WHERE schema_id = ? LIMIT 1";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, schemaId);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                return mapRow(rs);
            }

        } catch (Exception e) {
            log.error("Ошибка чтения плана по schemaId: {}", e.getMessage());
        }

        return null;
    }

    public Plan findById(String id) {
        String sql = "SELECT * FROM plans WHERE id = ? LIMIT 1";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, id);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                return mapRow(rs);
            }

        } catch (Exception e) {
            log.error("Ошибка чтения плана: {}", e.getMessage());
        }

        return null;
    }

    private Plan mapRow(ResultSet rs) throws Exception {
        Plan plan = new Plan();
        plan.setId(rs.getString("id"));
        plan.setWorkspaceId(rs.getString("workspace_id"));
        plan.setName(rs.getString("name"));
        plan.setParentId(rs.getString("parent_id"));
        plan.setSchemaId(rs.getString("schema_id"));
        String levelStr = rs.getString("level");
        if (levelStr != null) plan.setLevel(PlanLevel.valueOf(levelStr));
        plan.setTasks(mapper.readValue(rs.getString("tasks_json"),
                mapper.getTypeFactory().constructCollectionType(List.class, com.agent.orchestrator.model.Task.class)));
        String createdAt = rs.getString("created_at");
        if (createdAt != null) plan.setCreatedAt(Instant.parse(createdAt));
        String updatedAt = rs.getString("updated_at");
        if (updatedAt != null) plan.setUpdatedAt(Instant.parse(updatedAt));
        return plan;
    }
}
