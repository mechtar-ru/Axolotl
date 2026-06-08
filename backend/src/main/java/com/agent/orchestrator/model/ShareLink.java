package com.agent.orchestrator.model;

import lombok.Data;
import java.time.Instant;

@Data
public class ShareLink {
    private String id;
    private String schemaId;
    private String token;
    private Instant createdAt;
    private Instant expiresAt;
    private boolean readOnly;

    public ShareLink() {
        this.id = java.util.UUID.randomUUID().toString();
        this.createdAt = Instant.now();
        this.readOnly = true;
    }

    public ShareLink(String schemaId) {
        this();
        this.schemaId = schemaId;
        this.token = java.util.UUID.randomUUID().toString().replace("-", "");
    }
}
