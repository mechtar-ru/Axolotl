package com.agent.orchestrator.model;

import lombok.Data;
import java.time.Instant;

/**
 * Чекпоинт выполнения — сохраняется после каждой топологической волны.
 * Позволяет восстановить состояние при возобновлении после краша.
 */
@Data
public class ExecutionCheckpoint {
    private String id;
    private String runId;              // FK → execution_runs
    private String completedNodeIds;   // JSON array
    private int currentWave;           // номер волны
    private Instant createdAt;
}
