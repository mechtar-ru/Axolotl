package com.agent.orchestrator.repository;

import com.agent.orchestrator.config.DbConfig;
import com.agent.orchestrator.model.AppUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Repository
public class UserRepository {

    private static final Logger log = LoggerFactory.getLogger(UserRepository.class);

    private final ObjectMapper objectMapper;
    private final String dbUrl;

    public UserRepository(DbConfig dbConfig) {
        this.objectMapper = new ObjectMapper();
        this.dbUrl = dbConfig.getDbUrl();
        initDb();
    }

    private void initDb() {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id TEXT PRIMARY KEY,
                    username TEXT UNIQUE NOT NULL,
                    password TEXT NOT NULL,
                    role TEXT DEFAULT 'user'
                )
            """);
        } catch (SQLException e) {
            log.error("Error initializing users DB: {}", e.getMessage());
        }
    }

    public AppUser findByUsername(String username) {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE username = ?")) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return mapRow(rs);
            }
        } catch (SQLException e) {
            log.error("Error finding user: {}", e.getMessage());
        }
        return null;
    }

    public List<AppUser> findAll() {
        List<AppUser> users = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM users")) {
            while (rs.next()) {
                users.add(mapRow(rs));
            }
        } catch (SQLException e) {
            log.error("Error listing users: {}", e.getMessage());
        }
        return users;
    }

    public void save(AppUser user) {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT OR REPLACE INTO users (id, username, password, role) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, user.getId());
            ps.setString(2, user.getUsername());
            ps.setString(3, user.getPassword());
            ps.setString(4, user.getRole());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Error saving user: {}", e.getMessage());
        }
    }

    private AppUser mapRow(ResultSet rs) throws SQLException {
        return new AppUser(
                rs.getString("id"),
                rs.getString("username"),
                rs.getString("password"),
                rs.getString("role")
        );
    }
}
