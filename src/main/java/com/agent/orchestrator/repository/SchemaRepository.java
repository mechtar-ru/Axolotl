// repository/SchemaRepository.java
package com.agent.orchestrator.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.agent.orchestrator.model.WorkflowSchema;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Repository
public class SchemaRepository {
    
    private final String dbUrl = "jdbc:sqlite:schema.db";
    private final ObjectMapper mapper = new ObjectMapper();
    
    public SchemaRepository() {
        createTable();
    }
    
    private void createTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS schemas (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                data TEXT NOT NULL,
                created_at TEXT,
                updated_at TEXT
            )
            """;
        
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            System.out.println("✅ Таблица schemas создана/проверена");
        } catch (SQLException e) {
            System.err.println("Ошибка создания таблицы: " + e.getMessage());
        }
    }
    
    public void save(WorkflowSchema schema) {
        String sql = "INSERT OR REPLACE INTO schemas (id, name, data, created_at, updated_at) VALUES (?, ?, ?, ?, ?)";
        
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, schema.getId());
            pstmt.setString(2, schema.getName());
            pstmt.setString(3, mapper.writeValueAsString(schema));
            pstmt.setString(4, schema.getCreatedAt());
            pstmt.setString(5, schema.getUpdatedAt());
            pstmt.executeUpdate();
            
        } catch (Exception e) {
            System.err.println("Ошибка сохранения: " + e.getMessage());
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
            System.err.println("Ошибка чтения: " + e.getMessage());
        }
        
        return null;
    }
    
    public List<WorkflowSchema> findAll() {
        List<WorkflowSchema> schemas = new ArrayList<>();
        String sql = "SELECT data FROM schemas";
        
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                schemas.add(mapper.readValue(rs.getString("data"), WorkflowSchema.class));
            }
            
        } catch (Exception e) {
            System.err.println("Ошибка чтения: " + e.getMessage());
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
            System.err.println("Ошибка удаления: " + e.getMessage());
        }
    }
}