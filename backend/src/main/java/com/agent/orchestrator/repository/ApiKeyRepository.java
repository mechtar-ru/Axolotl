package com.agent.orchestrator.repository;

import com.agent.orchestrator.model.ApiKey;
import org.springframework.stereotype.Repository;

import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class ApiKeyRepository {
    private final Map<String, ApiKey> keysById = new ConcurrentHashMap<>();
    private final Map<String, ApiKey> keysByHash = new ConcurrentHashMap<>();

    public ApiKey save(ApiKey key) {
        keysById.put(key.getId(), key);
        keysByHash.put(key.getKeyHash(), key);
        return key;
    }

    public Optional<ApiKey> findById(String id) {
        return Optional.ofNullable(keysById.get(id));
    }

    public Optional<ApiKey> findByKeyHash(String keyHash) {
        return Optional.ofNullable(keysByHash.get(keyHash));
    }

    public List<ApiKey> findByUserId(String userId) {
        return keysById.values().stream()
                .filter(k -> userId.equals(k.getUserId()))
                .toList();
    }

    public void delete(String id) {
        ApiKey key = keysById.remove(id);
        if (key != null) {
            keysByHash.remove(key.getKeyHash());
        }
    }

    public static String hashKey(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(apiKey.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash API key", e);
        }
    }

    public static String getPrefix(String apiKey) {
        return apiKey.length() > 8 ? apiKey.substring(0, 8) : apiKey;
    }
}
