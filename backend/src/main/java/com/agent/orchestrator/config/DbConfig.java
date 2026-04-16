package com.agent.orchestrator.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

@Configuration
public class DbConfig {

    private static final Logger log = LoggerFactory.getLogger(DbConfig.class);

    private final String dbUrl;

    public DbConfig() {
        String projectDir = System.getProperty("user.dir");
        dbUrl = "jdbc:sqlite:schema.db";
        log.info("DbConfig dbUrl: {} (cwd: {})", dbUrl, projectDir);
    }

    @PostConstruct
    public void init() {
        enableWalMode();
    }

    private void enableWalMode() {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            log.info("SQLite WAL mode enabled");
        } catch (SQLException e) {
            log.warn("Could not enable WAL mode: {}", e.getMessage());
        }
    }

    public String getDbUrl() {
        return dbUrl;
    }
}
