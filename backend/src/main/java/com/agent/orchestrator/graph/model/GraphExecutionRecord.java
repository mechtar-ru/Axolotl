package com.agent.orchestrator.graph.model;

import org.springframework.data.annotation.Version;
import org.springframework.data.neo4j.core.schema.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

@Node("ExecutionRecord")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class GraphExecutionRecord {

    @Id
    @Property("id")
    private String id;

    @Version
    private Long version;

    @Property("schemaId")
    private String schemaId;

    @Property("schemaName")
    private String schemaName;

    @Property("startTime")
    private Instant startTime;

    @Property("endTime")
    private Instant endTime;

    @Property("totalTimeMs")
    private long totalTimeMs;

    @Property("totalNodes")
    private int totalNodes;

    @Property("completedNodes")
    private int completedNodes;

    @Property("status")
    private String status;

    @Property("totalTokens")
    private int totalTokens;

    @Property("estimatedCost")
    private double estimatedCost;













}
