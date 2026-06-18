package com.agent.orchestrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.agent.orchestrator.graph.model.GraphProviderSetting;
import com.agent.orchestrator.graph.repository.Neo4jProviderSettingRepository;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/**
 * Хранение настроек провайдеров (API ключи, URL) в Neo4j.
 * Позволяет обновлять ключи без перезапуска сервера.
 */
@Service
@Transactional
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);
    private static final String ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final String ENCRYPTION_KEY = System.getProperty(
            "axolotl.encryption.key", "Axolotl2026SecKey!"); // 16+ chars = AES-128

    private final Neo4jProviderSettingRepository neo4jRepo;
    private final SecretKeySpec aesKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public SettingsService(Neo4jProviderSettingRepository neo4jRepo) {
        this.neo4jRepo = neo4jRepo;
        byte[] keyBytes = ENCRYPTION_KEY.getBytes(StandardCharsets.UTF_8);
        byte[] aesKeyBytes = new byte[16];
        System.arraycopy(keyBytes, 0, aesKeyBytes, 0, Math.min(keyBytes.length, 16));
        this.aesKey = new SecretKeySpec(aesKeyBytes, "AES");
        // Ensure a sensible global default model exists. User requested DeepSeekV4 flash as default.
        try {
            String global = getGlobalDefaultModel();
            if (global == null || global.isBlank()) {
                setGlobalDefaultModel("deepseek-v4-flash-free");
                log.info("Global default model not set — defaulting to deepseek-v4-flash-free");
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

    public Map<String, Object> getProviderSettings(String providerName) {
        try {
            Optional<GraphProviderSetting> opt = neo4jRepo.findByProviderName(providerName);
            if (opt.isPresent()) {
                GraphProviderSetting g = opt.get();
                Map<String, Object> settings = new LinkedHashMap<>();
                settings.put("provider", g.getProviderName());
                settings.put("baseUrl", g.getBaseUrl());
                settings.put("defaultModel", g.getDefaultModel());
                String storedKey = g.getApiKey();
                settings.put("hasApiKey", storedKey != null && !storedKey.isBlank());
                return settings;
            }
        } catch (Exception e) {
            log.error("Ошибка чтения настроек: {}", e.getMessage());
        }
        return null;
    }

    public void updateProviderSettings(String providerName, String apiKey, String baseUrl, String defaultModel) {
        try {
            GraphProviderSetting g = new GraphProviderSetting();
            g.setProviderName(providerName);
            g.setApiKey(apiKey != null && !apiKey.isBlank() ? encrypt(apiKey) : apiKey);
            g.setBaseUrl(baseUrl);
            g.setDefaultModel(defaultModel);
            g.setUpdatedAt(java.time.Instant.now().toString());
            neo4jRepo.save(g);
            log.info("Настройки провайдера {} обновлены", providerName);
        } catch (Exception e) {
            throw new RuntimeException("Ошибка сохранения настроек: " + e.getMessage(), e);
        }
    }

    public String getApiKey(String providerName) {
        // NOTE: API keys are read from two sources:
        // 1. application.yml (via @Value) for built-in providers (OpenAI, etc.)
        // 2. System.getenv() here for legacy/fallback support
        // TODO: Unify to a single source of truth (application.yml + env mapping)
        // Check DB first
        try {
            Optional<GraphProviderSetting> opt = neo4jRepo.findByProviderName(providerName);
            if (opt.isPresent()) {
                String storedKey = opt.get().getApiKey();
                if (storedKey != null && !storedKey.isBlank()) {
                    return decrypt(storedKey);
                }
            }
        } catch (Exception e) {
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
        try {
            List<GraphProviderSetting> all = neo4jRepo.findAll();
            for (GraphProviderSetting g : all) {
                Map<String, Object> settings = new LinkedHashMap<>();
                settings.put("provider", g.getProviderName());
                String storedKey = g.getApiKey();
                settings.put("hasApiKey", storedKey != null && !storedKey.isBlank());
                settings.put("baseUrl", g.getBaseUrl());
                settings.put("defaultModel", g.getDefaultModel());
                result.add(settings);
            }
        } catch (Exception e) {
            log.error("Ошибка чтения всех настроек: {}", e.getMessage());
        }
        return result;
    }

    // --- Model toggle support ---

    public List<String> getDisabledModels(String providerName) {
        try {
            Optional<GraphProviderSetting> opt = neo4jRepo.findByProviderName(providerName);
            if (opt.isPresent()) {
                List<String> disabled = opt.get().getDisabledModels();
                return disabled != null ? disabled : List.of();
            }
        } catch (Exception e) {
            log.error("Ошибка чтения disabledModels: {}", e.getMessage());
        }
        return List.of();
    }

    public void setDisabledModels(String providerName, List<String> disabledModels) {
        try {
            Optional<GraphProviderSetting> opt = neo4jRepo.findByProviderName(providerName);
            GraphProviderSetting g = opt.orElseGet(() -> {
                GraphProviderSetting s = new GraphProviderSetting();
                s.setProviderName(providerName);
                return s;
            });
            g.setDisabledModels(disabledModels != null && !disabledModels.isEmpty() ? disabledModels : null);
            g.setUpdatedAt(java.time.Instant.now().toString());
            neo4jRepo.save(g);
        } catch (Exception e) {
            log.error("Ошибка сохранения disabledModels: {}", e.getMessage());
        }
    }

    // --- Model list persistence ---

    /**
     * Get persisted model list for a provider from Neo4j.
     */
    public List<String> getModels(String providerName) {
        try {
            Optional<GraphProviderSetting> opt = neo4jRepo.findByProviderName(providerName);
            if (opt.isPresent()) {
                List<String> models = opt.get().getModels();
                return models != null ? models : List.of();
            }
        } catch (Exception e) {
            log.error("Ошибка чтения models: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * Set model list for a provider in Neo4j. Only writes if the list actually
     * changed to avoid unnecessary Neo4j writes.
     */
    public void setModels(String providerName, List<String> models) {
        try {
            Optional<GraphProviderSetting> opt = neo4jRepo.findByProviderName(providerName);
            GraphProviderSetting g = opt.orElseGet(() -> {
                GraphProviderSetting s = new GraphProviderSetting();
                s.setProviderName(providerName);
                return s;
            });
            // Compare with existing to avoid unnecessary writes
            List<String> existing = g.getModels();
            if (listsEqual(existing, models)) {
                return;
            }
            g.setModels(models != null && !models.isEmpty() ? models : null);
            g.setUpdatedAt(java.time.Instant.now().toString());
            neo4jRepo.save(g);
        } catch (Exception e) {
            log.error("Ошибка сохранения models: {}", e.getMessage());
        }
    }

    // ──── Projects folder ────

    public String getProjectsFolder() {
        try {
            Optional<GraphProviderSetting> opt = neo4jRepo.findByProviderName("__projects__");
            if (opt.isPresent()) {
                String folder = opt.get().getProjectsFolder();
                return folder != null && !folder.isBlank() ? folder : null;
            }
        } catch (Exception e) {
            log.error("Error reading projects folder: {}", e.getMessage());
        }
        return null;
    }

    public void setProjectsFolder(String path) {
        try {
            GraphProviderSetting g = new GraphProviderSetting();
            g.setProviderName("__projects__");
            g.setProjectsFolder(path);
            g.setUpdatedAt(java.time.Instant.now().toString());
            neo4jRepo.save(g);
            log.info("Projects folder set to: {}", path);
        } catch (Exception e) {
            log.error("Error saving projects folder: {}", e.getMessage());
        }
    }

    private boolean listsEqual(List<String> a, List<String> b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        return a.containsAll(b) && b.containsAll(a);
    }
}
