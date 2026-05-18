package com.agent.orchestrator.repository;

import com.agent.orchestrator.config.DbConfig;
import com.agent.orchestrator.model.CustomLlmEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Repository
public class CustomLlmEndpointRepository {

    private static final Logger log = LoggerFactory.getLogger(CustomLlmEndpointRepository.class);

    private final String dbUrl;
    private final Map<String, CustomLlmEndpoint> cache = new HashMap<>();

    public CustomLlmEndpointRepository(DbConfig dbConfig) {
        this.dbUrl = dbConfig.getDbUrl();
        createTable();
        loadAll();
    }

    private void createTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS custom_llm_endpoints (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                base_url TEXT,
                api_key TEXT,
                model_name TEXT,
                auth_type TEXT DEFAULT 'bearer',
                enabled INTEGER DEFAULT 1,
                created_at TEXT,
                last_used_at TEXT,
                priority INTEGER DEFAULT 100
            )
            """;
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            // Add columns if missing (migrations)
            for (String col : new String[]{"auth_type", "priority", "last_used_at"}) {
                try { stmt.execute("ALTER TABLE custom_llm_endpoints ADD COLUMN " + col + " TEXT"); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            log.error("Error creating custom_llm_endpoints table: {}", e.getMessage());
        }
    }

    private void loadAll() {
        String sql = "SELECT * FROM custom_llm_endpoints";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                cache.put(rs.getString("id"), mapRow(rs));
            }
            log.info("Loaded {} custom LLM endpoints", cache.size());
        } catch (SQLException e) {
            log.error("Error loading custom endpoints: {}", e.getMessage());
        }
    }

    private CustomLlmEndpoint mapRow(ResultSet rs) throws SQLException {
        CustomLlmEndpoint e = new CustomLlmEndpoint();
        e.setId(rs.getString("id"));
        e.setName(rs.getString("name"));
        e.setBaseUrl(rs.getString("base_url"));
        e.setApiKey(rs.getString("api_key"));
        e.setModelName(rs.getString("model_name"));
        e.setAuthType(rs.getString("auth_type"));
        e.setEnabled(rs.getInt("enabled") == 1);
        String createdAt = rs.getString("created_at");
        if (createdAt != null) e.setCreatedAt(Instant.parse(createdAt));
        String lastUsedAt = rs.getString("last_used_at");
        if (lastUsedAt != null) e.setLastUsedAt(Instant.parse(lastUsedAt));
        e.setPriority(rs.getInt("priority"));
        return e;
    }

    public CustomLlmEndpoint save(CustomLlmEndpoint endpoint) {
        String sql = """
            INSERT OR REPLACE INTO custom_llm_endpoints (id, name, base_url, api_key, model_name, auth_type, enabled, created_at, last_used_at, priority)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, endpoint.getId());
            pstmt.setString(2, endpoint.getName());
            pstmt.setString(3, endpoint.getBaseUrl());
            pstmt.setString(4, endpoint.getApiKey());
            pstmt.setString(5, endpoint.getModelName());
            pstmt.setString(6, endpoint.getAuthType());
            pstmt.setInt(7, endpoint.isEnabled() ? 1 : 0);
            pstmt.setString(8, endpoint.getCreatedAt() != null ? endpoint.getCreatedAt().toString() : Instant.now().toString());
            pstmt.setString(9, endpoint.getLastUsedAt() != null ? endpoint.getLastUsedAt().toString() : null);
            pstmt.setInt(10, endpoint.getPriority());
            pstmt.executeUpdate();
            cache.put(endpoint.getId(), endpoint);
        } catch (SQLException ex) {
            log.error("Error saving custom endpoint: {}", ex.getMessage());
            throw new RuntimeException("Error saving custom endpoint: " + ex.getMessage(), ex);
        }
        return endpoint;
    }

    public Optional<CustomLlmEndpoint> findById(String id) {
        return Optional.ofNullable(cache.get(id));
    }

    public List<CustomLlmEndpoint> findAll() {
        return new ArrayList<>(cache.values());
    }

    public List<CustomLlmEndpoint> findEnabled() {
        return cache.values().stream()
                .filter(CustomLlmEndpoint::isEnabled)
                .sorted(Comparator.comparingInt(CustomLlmEndpoint::getPriority))
                .collect(Collectors.toList());
    }

    public Optional<CustomLlmEndpoint> findByName(String name) {
        return cache.values().stream()
                .filter(e -> name != null && name.equalsIgnoreCase(e.getName()))
                .findFirst();
    }

    public boolean existsByName(String name) {
        return cache.values().stream()
                .anyMatch(e -> name != null && name.equalsIgnoreCase(e.getName()));
    }

    public boolean existsByNameExcludingId(String name, String excludeId) {
        return cache.values().stream()
                .anyMatch(e -> name != null && name.equalsIgnoreCase(e.getName()) && !e.getId().equals(excludeId));
    }

    public void delete(String id) {
        String sql = "DELETE FROM custom_llm_endpoints WHERE id = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, id);
            pstmt.executeUpdate();
            cache.remove(id);
        } catch (SQLException e) {
            log.error("Error deleting custom endpoint: {}", e.getMessage());
        }
    }
}
