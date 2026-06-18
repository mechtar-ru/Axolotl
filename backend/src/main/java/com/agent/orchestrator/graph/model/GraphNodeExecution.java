package com.agent.orchestrator.graph.model;

import org.springframework.data.annotation.Version;
import org.springframework.data.neo4j.core.schema.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

@Node("NodeExecution")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class GraphNodeExecution {

    @Id
    @Property("id")
    private String id;

    @Property("runId")
    private String runId;

    @Property("nodeId")
    private String nodeId;

    @Property("nodeName")
    private String nodeName;

    @Property("nodeType")
    private String nodeType;

    @Property("status")
    private String status;

    @Property("tokensUsed")
    private long tokensUsed;

    @Property("durationMs")
    private long durationMs;

    @Property("toolCalls")
    private int toolCalls;

    @Property("error")
    private String error;

    @Property("inputSummary")
    private String inputSummary;

    @Property("outputSummary")
    private String outputSummary;

    @Property("filesWritten")
    private String filesWritten;

    @Property("configHash")
    private String configHash;

    @Property("startedAt")
    private Instant startedAt;

    @Property("completedAt")
    private Instant completedAt;

    @Property("reasoning")
    private String reasoning;

    @Version
    private Long version;














}
