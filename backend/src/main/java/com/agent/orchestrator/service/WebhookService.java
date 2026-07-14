package com.agent.orchestrator.service;

import com.agent.orchestrator.model.WorkflowSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Sends webhook notifications on execution events (completion, failure).
 * Configured via ApiKey.webhookUrl.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();

    private final FeatureFlagService featureFlags;
    private final com.agent.orchestrator.graph.repository.Neo4jSchemaRepository schemaRepository;

    @Value("${axolotl.features.webhook.url:}")
    private String webhookUrl;

    public WebhookService(FeatureFlagService featureFlags, 
                          com.agent.orchestrator.graph.repository.Neo4jSchemaRepository schemaRepository) {
        this.featureFlags = featureFlags;
        this.schemaRepository = schemaRepository;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    /**
     * Send webhook for execution completion.
     * Called asynchronously from the pipeline completion handler.
     */
    @Async
    public void sendCompletionWebhook(String schemaId, String status, String error,
                                       String webhookUrl, Map<String, Object> metadata) {
        if (!featureFlags.isEnabled("webhook")) return;
        if (webhookUrl == null || webhookUrl.isBlank()) return;

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("event", "execution.completed");
        payload.put("schemaId", schemaId);
        payload.put("status", status);
        payload.put("error", error);
        payload.put("timestamp", Instant.now().toString());
        if (metadata != null) payload.put("metadata", metadata);

        WorkflowSchema schema = schemaRepository.findById(schemaId);
        if (schema != null) {
            payload.put("schemaName", schema.getName());
            payload.put("defaultModel", schema.getDefaultModel());
        }

        sendWithRetry(webhookUrl, payload);
    }

    /**
     * Send webhook for execution failure.
     */
    @Async
    public void sendFailureWebhook(String schemaId, String error, String webhookUrl) {
        sendCompletionWebhook(schemaId, "failed", error, webhookUrl, null);
    }

    /**
     * Send webhook for review approval request.
     */
    @Async
    public void sendApprovalRequestWebhook(String schemaId, String nodeId, String webhookUrl) {
        if (!featureFlags.isEnabled("webhook")) return;
        if (webhookUrl == null || webhookUrl.isBlank()) return;

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("event", "review.approval_requested");
        payload.put("schemaId", schemaId);
        payload.put("nodeId", nodeId);
        payload.put("timestamp", Instant.now().toString());

        sendWithRetry(webhookUrl, payload);
    }

    private void sendWithRetry(String url, Map<String, Object> payload) {
        CompletableFuture.runAsync(() -> {
            for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
                try {
                    String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("Content-Type", "application/json")
                            .header("User-Agent", "Axolotl/0.4.0")
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .timeout(java.time.Duration.ofSeconds(10))
                            .build();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        log.info("Webhook delivered to {}: status={}", url, response.statusCode());
                        return;
                    }
                    log.warn("Webhook to {} returned status {} (attempt {}/{})", url, response.statusCode(), attempt + 1, MAX_RETRIES);
                } catch (Exception e) {
                    log.warn("Webhook to {} failed (attempt {}/{}): {}", url, attempt + 1, MAX_RETRIES, e.getMessage());
                }
                try {
                    Thread.sleep(RETRY_DELAY_MS * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            log.error("Webhook to {} failed after {} attempts", url, MAX_RETRIES);
        });
    }
}
