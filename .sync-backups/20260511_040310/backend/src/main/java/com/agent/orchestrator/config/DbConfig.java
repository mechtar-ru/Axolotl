package com.agent.orchestrator.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

@Configuration
public class DbConfig {

    private static final Logger log = LoggerFactory.getLogger(DbConfig.class);

    private final String dbUrl;

    public DbConfig(@Value("${axolotl.db.path:}") String dbPath) {
        if (dbPath != null && !dbPath.isBlank()) {
            // Env var provided — resolve to absolute
            Path resolved = Paths.get(dbPath).toAbsolutePath();
            dbUrl = "jdbc:sqlite:" + resolved;
        } else {
            // Default: ~/.axolotl/schema.db (OS-agnostic, survives app updates)
            Path dataDir = Paths.get(System.getProperty("user.home"), ".axolotl");
            dataDir.toFile().mkdirs();
            dbUrl = "jdbc:sqlite:" + dataDir.resolve("schema.db");
        }
        log.info("DbConfig dbUrl: {}", dbUrl);
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
