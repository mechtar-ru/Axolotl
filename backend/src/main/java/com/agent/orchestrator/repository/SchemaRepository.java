// repository/SchemaRepository.java
package com.agent.orchestrator.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    public SchemaRepository() {
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
            CREATE TABLE IF NOT EXISTS schemas (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                user_id TEXT,
                data TEXT NOT NULL,
                created_at TEXT,
                updated_at TEXT
            )
            """;

        // Add user_id column if it doesn't exist (migration for existing DB)
        String alterSql = "ALTER TABLE schemas ADD COLUMN user_id TEXT";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            try {
                stmt.execute(alterSql);
            } catch (SQLException e) {
                // Column already exists — ignore
            }
            log.info("Таблица schemas создана/проверена");
        } catch (SQLException e) {
            log.error("Ошибка создания таблицы: {}", e.getMessage());
        }
    }
    
    public void save(WorkflowSchema schema) {
        String sql = "INSERT OR REPLACE INTO schemas (id, name, user_id, data, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, schema.getId());
            pstmt.setString(2, schema.getName());
            pstmt.setString(3, schema.getUserId());
            pstmt.setString(4, mapper.writeValueAsString(schema));
            pstmt.setString(5, schema.getCreatedAt());
            pstmt.setString(6, schema.getUpdatedAt());
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
        return findByUserId(null);
    }

    /**
     * Find all schemas for a specific user.
     * If userId is null, returns all schemas (backward compatibility).
     */
    public List<WorkflowSchema> findByUserId(String userId) {
        List<WorkflowSchema> schemas = new ArrayList<>();
        String sql = userId != null
                ? "SELECT data FROM schemas WHERE user_id = ? OR user_id IS NULL"
                : "SELECT data FROM schemas";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            if (userId != null) {
                pstmt.setString(1, userId);
            }
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
}