package com.agent.orchestrator.graph.model;

import org.springframework.data.annotation.Version;
import org.springframework.data.neo4j.core.schema.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

@Node("ExecutionRun")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class GraphExecutionRun {

    @Id
    @Property("id")
    private String id;

    @Version
    private Long version;

    @Property("schemaId")
    private String schemaId;

    @Property("status")
    private String status;

    @Property("mode")
    private String mode;

    @Property("totalTokens")
    private long totalTokens;

    @Property("estimatedCost")
    private double estimatedCost;

    @Property("error")
    private String error;

    @Property("resumesFrom")
    private String resumesFrom;

    @Property("startedAt")
    private Instant startedAt;

    @Property("updatedAt")
    private Instant updatedAt;

    @Property("completedAt")
    private Instant completedAt;

    @Property("stageStatus")
    private String stageStatusJson = "{}";

    @Property("stageOutputs")
    private String stageOutputsJson = "{}";

    @Property("resumeIndex")
    private int resumeIndex = -1;

    @Property("generatedFiles")
    private String generatedFilesJson = "[]";


    public GraphExecutionRun(String id, String schemaId, String status, String mode) {
        this.id = id;
        this.schemaId = schemaId;
        this.status = status;
        this.mode = mode;
    }

}
