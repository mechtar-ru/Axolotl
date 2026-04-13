package com.agent.orchestrator.repository;

import com.agent.orchestrator.model.Plan;
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

    public PlanRepository() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        // Use absolute path to schema.db in backend directory
        String projectDir = System.getProperty("user.dir");
        if (projectDir.endsWith("backend")) {
            dbUrl = "jdbc:sqlite:schema.db";
        } else {
            dbUrl = "jdbc:sqlite:backend/schema.db";
        }
        createTable();
    }

    private void createTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS plans (
                id TEXT PRIMARY KEY,
                workspace_id TEXT NOT NULL,
                name TEXT NOT NULL,
                tasks_json TEXT NOT NULL,
                created_at TEXT,
                updated_at TEXT,
                UNIQUE(workspace_id, name)
            )
            """;

        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            log.info("Таблица plans создана/проверена");
        } catch (SQLException e) {
            log.error("Ошибка создания таблицы plans: {}", e.getMessage());
        }
    }

    public void save(Plan plan) {
        String sql = """
            INSERT INTO plans (id, workspace_id, name, tasks_json, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(workspace_id, name) DO UPDATE SET
                id = excluded.id,
                tasks_json = excluded.tasks_json,
                updated_at = excluded.updated_at
            """;

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, plan.getId());
            pstmt.setString(2, plan.getWorkspaceId());
            pstmt.setString(3, plan.getName());
            pstmt.setString(4, mapper.writeValueAsString(plan.getTasks()));
            pstmt.setString(5, plan.getCreatedAt() != null ? plan.getCreatedAt().toString() : null);
            pstmt.setString(6, plan.getUpdatedAt() != null ? plan.getUpdatedAt().toString() : null);
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

    private Plan mapRow(ResultSet rs) throws Exception {
        Plan plan = new Plan();
        plan.setId(rs.getString("id"));
        plan.setWorkspaceId(rs.getString("workspace_id"));
        plan.setName(rs.getString("name"));
        plan.setTasks(mapper.readValue(rs.getString("tasks_json"),
                mapper.getTypeFactory().constructCollectionType(List.class, com.agent.orchestrator.model.Task.class)));
        String createdAt = rs.getString("created_at");
        if (createdAt != null) plan.setCreatedAt(Instant.parse(createdAt));
        String updatedAt = rs.getString("updated_at");
        if (updatedAt != null) plan.setUpdatedAt(Instant.parse(updatedAt));
        return plan;
    }
}
