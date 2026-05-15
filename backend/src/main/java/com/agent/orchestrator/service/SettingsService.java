package com.agent.orchestrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.agent.orchestrator.config.DbConfig;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;

/**
 * Хранение настроек провайдеров (API ключи, URL) в SQLite.
 * Позволяет обновлять ключи без перезапуска сервера.
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);
    private static final String ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final String ENCRYPTION_KEY = System.getProperty(
            "axolotl.encryption.key", "Axolotl2026SecKey!"); // 16+ chars = AES-128

    private final String dbUrl;
    private final SecretKeySpec aesKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public SettingsService(DbConfig dbConfig) {
        this.dbUrl = dbConfig.getDbUrl();
        byte[] keyBytes = ENCRYPTION_KEY.getBytes(StandardCharsets.UTF_8);
        byte[] aesKeyBytes = new byte[16];
        System.arraycopy(keyBytes, 0, aesKeyBytes, 0, Math.min(keyBytes.length, 16));
        this.aesKey = new SecretKeySpec(aesKeyBytes, "AES");
        createTable();
        // Ensure a sensible global default model exists. User requested DeepSeekV4 flash as default.
        try {
            String global = getGlobalDefaultModel();
            if (global == null || global.isBlank()) {
                setGlobalDefaultModel("deepseek-v4-flash");
                log.info("Global default model not set — defaulting to deepseek-v4-flash");
            } else {
                log.info("Global default model already set: {}", global);
            }
        } catch (Exception e) {
            log.warn("Unable to ensure global default model: {}", e.getMessage());
        }
    }

    private String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return plaintext;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            // Prepend IV to ciphertext
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("Encryption failed: {}", e.getMessage());
            return plaintext;
        }
    }

    private String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) return ciphertext;
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            if (combined.length < GCM_IV_LENGTH) return ciphertext; // Not encrypted
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] encrypted = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Might be plaintext (old data) — return as-is
            return ciphertext;
        }
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
                String storedKey = rs.getString("api_key");
                settings.put("hasApiKey", storedKey != null && !storedKey.isBlank());
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
            pstmt.setString(2, apiKey != null && !apiKey.isBlank() ? encrypt(apiKey) : apiKey);
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
        // Check DB first
        String sql = "SELECT api_key FROM provider_settings WHERE provider_name = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, providerName);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                String storedKey = rs.getString("api_key");
                if (storedKey != null && !storedKey.isBlank()) {
                    return decrypt(storedKey);
                }
            }
        } catch (SQLException e) {
            log.error("Ошибка чтения API ключа: {}", e.getMessage());
        }
        // Fallback: env vars
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
        return null;
    }

    public String getDefaultModel(String providerName) {
        Map<String, Object> settings = getProviderSettings(providerName);
        if (settings != null && settings.get("defaultModel") != null) {
            return (String) settings.get("defaultModel");
        }
        return null;
    }

    public String getGlobalDefaultModel() {
        return getDefaultModel("__global__");
    }

    public void setGlobalDefaultModel(String model) {
        updateProviderSettings("__global__", null, null, model);
    }

    public String getUserDefaultModel(String userId) {
        return getDefaultModel("__user__:" + userId);
    }

    public void setUserDefaultModel(String userId, String model) {
        updateProviderSettings("__user__:" + userId, null, null, model);
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
                String storedKey = rs.getString("api_key");
                settings.put("hasApiKey", storedKey != null && !storedKey.isBlank());
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
