// repository/SchemaRepository.java
package com.agent.orchestrator.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.agent.orchestrator.config.DbConfig;
import com.agent.orchestrator.model.WorkflowSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Repository
public class SchemaRepository {

    private static final Logger log = LoggerFactory.getLogger(SchemaRepository.class);

    private final String dbUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public SchemaRepository(DbConfig dbConfig) {
        this.dbUrl = dbConfig.getDbUrl();
        createTable();
    }
    
    private void createTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS schemas (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                user_id TEXT,
                workspace_id TEXT,
                data TEXT NOT NULL,
                created_at TEXT,
                updated_at TEXT
            )
            """;

        String alterUserSql = "ALTER TABLE schemas ADD COLUMN user_id TEXT";
        String alterWsSql = "ALTER TABLE schemas ADD COLUMN workspace_id TEXT";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            try { stmt.execute(alterUserSql); } catch (SQLException ignored) {}
            try { stmt.execute(alterWsSql); } catch (SQLException ignored) {}
            // Migrate existing schemas to "default" workspace
            try {
                stmt.execute("UPDATE schemas SET workspace_id = 'default' WHERE workspace_id IS NULL");
            } catch (SQLException ignored) {}
            log.info("Таблица schemas создана/проверена");
        } catch (SQLException e) {
            log.error("Ошибка создания таблицы: {}", e.getMessage());
        }
    }
    
    public void save(WorkflowSchema schema) {
        String sql = "INSERT OR REPLACE INTO schemas (id, name, user_id, workspace_id, data, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, schema.getId());
            pstmt.setString(2, schema.getName());
            pstmt.setString(3, schema.getUserId());
            pstmt.setString(4, schema.getWorkspaceId() != null ? schema.getWorkspaceId() : "default");
            pstmt.setString(5, mapper.writeValueAsString(schema));
            pstmt.setString(6, schema.getCreatedAt());
            pstmt.setString(7, schema.getUpdatedAt());
            pstmt.executeUpdate();

        } catch (Exception e) {
            log.error("Ошибка сохранения: {}", e.getMessage());
        }
    }
    
    public WorkflowSchema findById(String id) {
        String sql = "SELECT data FROM schemas WHERE id = ?";
        
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, id);
            ResultSet rs = pstmt.executeQuery();
            
            if (rs.next()) {
                return mapper.readValue(rs.getString("data"), WorkflowSchema.class);
            }
            
        } catch (Exception e) {
            log.error("Ошибка чтения: {}", e.getMessage());
        }
        
        return null;
    }
    
    public List<WorkflowSchema> findAll() {
        return findByUserId(null, null);
    }

    public List<WorkflowSchema> findByWorkspaceId(String workspaceId) {
        return findByUserId(null, workspaceId);
    }

    public List<String> findAllWorkspaceIds() {
        List<String> ids = new ArrayList<>();
        String sql = "SELECT DISTINCT workspace_id FROM schemas WHERE workspace_id IS NOT NULL ORDER BY workspace_id";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString("workspace_id"));
                }
            }
            // Ensure "default" is always present
            if (!ids.contains("default")) {
                ids.add(0, "default");
            }

        } catch (Exception e) {
            log.error("Ошибка чтения workspaces: {}", e.getMessage());
        }

        return ids;
    }

    /**
     * Find all schemas for a specific user and/or workspace.
     * If both are null, returns all schemas.
     */
    public List<WorkflowSchema> findByUserId(String userId, String workspaceId) {
        List<WorkflowSchema> schemas = new ArrayList<>();

        String baseSql = "SELECT data FROM schemas WHERE 1=1";
        List<String> conditions = new ArrayList<>();
        if (userId != null) conditions.add("(user_id = ? OR user_id IS NULL)");
        if (workspaceId != null) conditions.add("(workspace_id = ? OR workspace_id IS NULL)");
        
        String sql = baseSql + (conditions.isEmpty() ? "" : " AND " + String.join(" AND ", conditions));

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            int idx = 1;
            if (userId != null) pstmt.setString(idx++, userId);
            if (workspaceId != null) pstmt.setString(idx++, workspaceId);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    schemas.add(mapper.readValue(rs.getString("data"), WorkflowSchema.class));
                }
            }

        } catch (Exception e) {
            log.error("Ошибка чтения: {}", e.getMessage());
        }

        return schemas;
    }
    
    public void delete(String id) {
        String sql = "DELETE FROM schemas WHERE id = ?";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, id);
            pstmt.executeUpdate();

        } catch (SQLException e) {
            log.error("Ошибка удаления: {}", e.getMessage());
        }
    }

    public void deleteAll() {
        String sql = "DELETE FROM schemas";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        } catch (SQLException e) {
            log.error("Ошибка очистки таблицы: {}", e.getMessage());
        }
    }
}