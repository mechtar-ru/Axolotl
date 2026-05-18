package com.agent.orchestrator.model;

import java.time.Instant;

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

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSchemaId() { return schemaId; }
    public void setSchemaId(String schemaId) { this.schemaId = schemaId; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
}
