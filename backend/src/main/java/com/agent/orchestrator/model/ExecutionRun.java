package com.agent.orchestrator.model;

import lombok.Data;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Сущность запуска выполнения схемы.
 * Один "клик по Run" = один ExecutionRun.
 * При возобновлении создаётся новый ExecutionRun со ссылкой на родительский.
 */
@Data
public class ExecutionRun {
    private String id;
    private String schemaId;
    private String status;       // running | paused | completed | failed | cancelled
    private String mode;         // EXECUTE | ANALYZE | DRY_RUN
    private long totalTokens;
    private double estimatedCost;
    private String error;
    private String resumesFrom;  // nullable FK → parent run id
    private Long version;
    private List<String> generatedFiles;
    private Instant startedAt;
    private Instant updatedAt;
    private Instant completedAt;
    private Map<String, String> stageStatus = new HashMap<>();
    private Map<String, String> stageOutputs = new HashMap<>();
    private int resumeIndex = -1;     // -1 = not set; stores the stage index to resume from
}
