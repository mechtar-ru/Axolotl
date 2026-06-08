package com.agent.orchestrator.model;

import lombok.Data;
import java.time.Instant;

@Data
public class CustomLlmEndpoint {
    private String id;
    private String name;
    private String baseUrl;
    private String apiKey;
    private String modelName;
    private String authType;
    private boolean enabled;
    private Instant createdAt;
    private Instant lastUsedAt;
    private int priority;

    public CustomLlmEndpoint() {
        this.id = java.util.UUID.randomUUID().toString();
        this.createdAt = Instant.now();
        this.enabled = true;
        this.authType = "bearer";
        this.priority = 100;
    }
}
