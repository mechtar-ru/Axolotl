package com.agent.orchestrator.model;

import lombok.Data;
import java.time.Instant;

/**
 * Результат выполнения одного узла схемы в рамках конкретного запуска.
 * Персистентная запись — сохраняется после каждого завершённого узла.
 */
@Data
public class NodeExecution {
    private String id;
    private String runId;        // FK → execution_runs
    private String nodeId;       // ID узла в схеме
    private String nodeName;     // denormalized для отображения
    private String nodeType;     // denormalized (agent, source, output, etc.)
    private String status;       // pending | running | completed | failed | skipped
    private long tokensUsed;
    private long durationMs;
    private int toolCalls;
    private String error;
    private String inputSummary;   // JSON snapshot
    private String outputSummary;  // JSON snapshot
    private String filesWritten;   // JSON array [{path, description}]
    private String configHash;     // SHA256 конфига узла на момент выполнения
    private Instant startedAt;
    private Instant completedAt;
    private String reasoning;      // LLM reasoning/thought content (nullable)
}
