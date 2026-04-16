package com.agent.orchestrator.controller;

import com.agent.orchestrator.model.ApiKey;
import com.agent.orchestrator.model.ExecutionMode;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.repository.ApiKeyRepository;
import com.agent.orchestrator.service.SchemaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/remote")
public class RemoteApiController {
    private static final Logger log = LoggerFactory.getLogger(RemoteApiController.class);
    private static final int MAX_REQUESTS_PER_MINUTE = 60;

    private final ApiKeyRepository apiKeyRepository;
    private final SchemaService schemaService;
    private final Map<String, AtomicInteger> rateLimitCounts = new ConcurrentHashMap<>();
    private final Map<String, Long> rateLimitWindows = new ConcurrentHashMap<>();

    public RemoteApiController(ApiKeyRepository apiKeyRepository, SchemaService schemaService) {
        this.apiKeyRepository = apiKeyRepository;
        this.schemaService = schemaService;
    }

    @PostMapping("/keys")
    public Map<String, Object> createApiKey(@RequestBody Map<String, Object> request,
                                            @RequestHeader("Authorization") String auth) {
        String userId = extractUserId(auth);
        String name = (String) request.getOrDefault("name", "API Key");
        String[] scopes = request.containsKey("scopes")
                ? ((List<String>) request.get("scopes")).toArray(new String[0])
                : new String[] { "workflows:read", "workflows:execute" };
        String webhookUrl = (String) request.get("webhookUrl");

        String rawKey = "axk_" + UUID.randomUUID().toString().replace("-", "");
        String keyHash = ApiKeyRepository.hashKey(rawKey);
        String keyPrefix = ApiKeyRepository.getPrefix(rawKey);

        ApiKey apiKey = new ApiKey(name, keyHash, keyPrefix, userId);
        apiKey.setScopes(scopes);
        apiKey.setWebhookUrl(webhookUrl);

        if (request.containsKey("expiresInDays")) {
            int days = (Integer) request.get("expiresInDays");
            apiKey.setExpiresAt(Instant.now().plusSeconds(days * 86400L));
        }

        apiKeyRepository.save(apiKey);

        log.info("API key created: {} for user {}", name, userId);

        Map<String, Object> response = new HashMap<>();
        response.put("id", apiKey.getId());
        response.put("name", apiKey.getName());
        response.put("key", rawKey);
        response.put("keyPrefix", keyPrefix);
        response.put("scopes", apiKey.getScopes());
        response.put("createdAt", apiKey.getCreatedAt().toString());
        response.put("expiresAt", apiKey.getExpiresAt() != null ? apiKey.getExpiresAt().toString() : null);
        response.put("warning", "Store this key securely. It will not be shown again.");

        return response;
    }

    @GetMapping("/keys")
    public List<Map<String, Object>> listApiKeys(@RequestHeader("Authorization") String auth) {
        String userId = extractUserId(auth);
        return apiKeyRepository.findByUserId(userId).stream()
                .map(this::toKeyInfo)
                .toList();
    }

    @DeleteMapping("/keys/{id}")
    public void deleteApiKey(@PathVariable String id, @RequestHeader("Authorization") String auth) {
        String userId = extractUserId(auth);
        ApiKey key = apiKeyRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!userId.equals(key.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        apiKeyRepository.delete(id);
        log.info("API key deleted: {}", id);
    }

    @PostMapping("/workflows/{id}/run")
    public Map<String, String> runWorkflow(@PathVariable String id,
                                           @RequestHeader("X-API-Key") String apiKey,
                                           @RequestHeader(value = "X-Execution-Mode", defaultValue = "EXECUTE") String mode,
                                           @RequestBody(required = false) Map<String, Object> input) {
        checkRateLimit(apiKey);
        ApiKey key = validateApiKey(apiKey, "workflows:execute");

        checkRateLimit(key.getId());

        ExecutionMode executionMode = ExecutionMode.valueOf(mode);
        schemaService.executeSchema(id, executionMode);

        Map<String, String> response = new HashMap<>();
        response.put("status", "started");
        response.put("workflowId", id);
        response.put("mode", executionMode.name());

        log.info("Remote workflow started: {} by key {}", id, key.getKeyPrefix());

        return response;
    }

    @GetMapping("/workflows/{id}/status")
    public Map<String, Object> getWorkflowStatus(@PathVariable String id,
                                                 @RequestHeader("X-API-Key") String apiKey) {
        checkRateLimit(apiKey);
        validateApiKey(apiKey, "workflows:read");

        Map<String, Object> status = new HashMap<>();
        status.put("workflowId", id);

        var history = schemaService.getExecutionHistory(id);
        if (!history.isEmpty()) {
            var last = history.get(0);
            status.put("lastRun", Map.of(
                    "startTime", last.getStartTime(),
                    "endTime", last.getEndTime(),
                    "status", last.getStatus(),
                    "completedNodes", last.getCompletedNodes(),
                    "totalNodes", last.getTotalNodes()
            ));
        }

        return status;
    }

    @PostMapping("/workflows/{id}/stop")
    public Map<String, String> stopWorkflow(@PathVariable String id,
                                            @RequestHeader("X-API-Key") String apiKey) {
        checkRateLimit(apiKey);
        validateApiKey(apiKey, "workflows:execute");

        schemaService.cancelExecution(id);

        Map<String, String> response = new HashMap<>();
        response.put("status", "stopped");
        response.put("workflowId", id);

        return response;
    }

    @GetMapping("/workflows")
    public List<WorkflowSchema> listWorkflows(@RequestHeader("X-API-Key") String apiKey) {
        checkRateLimit(apiKey);
        validateApiKey(apiKey, "workflows:read");

        return schemaService.getAllSchemas();
    }

    private ApiKey validateApiKey(String rawKey, String requiredScope) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "API key required");
        }

        String keyHash = ApiKeyRepository.hashKey(rawKey);
        ApiKey key = apiKeyRepository.findByKeyHash(keyHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API key"));

        if (!key.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "API key disabled");
        }

        if (key.getExpiresAt() != null && key.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "API key expired");
        }

        boolean hasScope = Arrays.asList(key.getScopes()).contains(requiredScope);
        if (!hasScope) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Insufficient scope: " + requiredScope);
        }

        return key;
    }

    private void checkRateLimit(String apiKey) {
        String keyHash = ApiKeyRepository.hashKey(apiKey);
        long now = System.currentTimeMillis() / 60000;
        AtomicInteger count = rateLimitCounts.computeIfAbsent(keyHash, k -> new AtomicInteger(0));
        Long window = rateLimitWindows.get(keyHash);

        if (window == null || window < now) {
            count.set(0);
            rateLimitWindows.put(keyHash, now);
        }

        if (count.incrementAndGet() > MAX_REQUESTS_PER_MINUTE) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
        }
    }

    private String extractUserId(String auth) {
        return "remote-user";
    }

    private Map<String, Object> toKeyInfo(ApiKey key) {
        Map<String, Object> info = new HashMap<>();
        info.put("id", key.getId());
        info.put("name", key.getName());
        info.put("keyPrefix", key.getKeyPrefix());
        info.put("scopes", key.getScopes());
        info.put("enabled", key.isEnabled());
        info.put("createdAt", key.getCreatedAt().toString());
        info.put("expiresAt", key.getExpiresAt() != null ? key.getExpiresAt().toString() : null);
        info.put("webhookUrl", key.getWebhookUrl());
        return info;
    }
}
