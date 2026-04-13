package com.agent.orchestrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

/**
 * Хранение настроек провайдеров (API ключи, URL) в SQLite.
 * Позволяет обновлять ключи без перезапуска сервера.
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private final String dbUrl;

    public SettingsService() {
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
            CREATE TABLE IF NOT EXISTS provider_settings (
                provider_name TEXT PRIMARY KEY,
                api_key TEXT,
                base_url TEXT,
                default_model TEXT,
                updated_at TEXT
            )
            """;
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            log.error("Ошибка создания таблицы provider_settings: {}", e.getMessage());
        }
    }

    public Map<String, Object> getProviderSettings(String providerName) {
        String sql = "SELECT * FROM provider_settings WHERE provider_name = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, providerName);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                Map<String, Object> settings = new LinkedHashMap<>();
                settings.put("provider", rs.getString("provider_name"));
                settings.put("baseUrl", rs.getString("base_url"));
                settings.put("defaultModel", rs.getString("default_model"));
                // НЕ возвращаем api_key в списке — только через отдельный запрос
                settings.put("hasApiKey", rs.getString("api_key") != null && !rs.getString("api_key").isBlank());
                return settings;
            }
        } catch (SQLException e) {
            log.error("Ошибка чтения настроек: {}", e.getMessage());
        }
        return null;
    }

    public void updateProviderSettings(String providerName, String apiKey, String baseUrl, String defaultModel) {
        String sql = """
            INSERT INTO provider_settings (provider_name, api_key, base_url, default_model, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(provider_name) DO UPDATE SET
                api_key = excluded.api_key,
                base_url = excluded.base_url,
                default_model = excluded.default_model,
                updated_at = excluded.updated_at
            """;
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, providerName);
            pstmt.setString(2, apiKey);
            pstmt.setString(3, baseUrl);
            pstmt.setString(4, defaultModel);
            pstmt.setString(5, java.time.Instant.now().toString());
            pstmt.executeUpdate();
            log.info("Настройки провайдера {} обновлены", providerName);
        } catch (SQLException e) {
            throw new RuntimeException("Ошибка сохранения настроек: " + e.getMessage(), e);
        }
    }

    public String getApiKey(String providerName) {
        // Сначала проверяем БД
        Map<String, Object> settings = getProviderSettings(providerName);
        if (settings != null && Boolean.TRUE.equals(settings.get("hasApiKey"))) {
            String sql = "SELECT api_key FROM provider_settings WHERE provider_name = ?";
            try (Connection conn = DriverManager.getConnection(dbUrl);
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, providerName);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    return rs.getString("api_key");
                }
            } catch (SQLException e) {
                log.error("Ошибка чтения API ключа: {}", e.getMessage());
            }
        }
        // Fallback: переменные окружения
        String envKey = switch (providerName) {
            case "openai" -> System.getenv("OPENAI_API_KEY");
            case "anthropic" -> System.getenv("ANTHROPIC_API_KEY");
            case "deepseek" -> System.getenv("DEEPSEEK_API_KEY");
            default -> null;
        };
        return envKey;
    }

    public String getBaseUrl(String providerName) {
        Map<String, Object> settings = getProviderSettings(providerName);
        if (settings != null && settings.get("baseUrl") != null) {
            return (String) settings.get("baseUrl");
        }
        return null; // Fallback к application.yml
    }

    public String getDefaultModel(String providerName) {
        Map<String, Object> settings = getProviderSettings(providerName);
        if (settings != null && settings.get("defaultModel") != null) {
            return (String) settings.get("defaultModel");
        }
        return null; // Fallback к application.yml
    }

    public List<Map<String, Object>> getAllProviderSettings() {
        List<Map<String, Object>> result = new ArrayList<>();
        String sql = "SELECT * FROM provider_settings";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> settings = new LinkedHashMap<>();
                settings.put("provider", rs.getString("provider_name"));
                settings.put("hasApiKey", rs.getString("api_key") != null && !rs.getString("api_key").isBlank());
                settings.put("baseUrl", rs.getString("base_url"));
                settings.put("defaultModel", rs.getString("default_model"));
                result.add(settings);
            }
        } catch (SQLException e) {
            log.error("Ошибка чтения всех настроек: {}", e.getMessage());
        }
        return result;
    }
}
