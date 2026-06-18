package com.agent.orchestrator.graph.model;

import org.springframework.data.annotation.Version;
import org.springframework.data.neo4j.core.schema.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

@Node("Checkpoint")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class GraphCheckpoint {

    @Id
    @Property("id")
    private String id;

    @Property("runId")
    private String runId;

    @Property("completedNodeIds")
    private String completedNodeIds;

    @Property("currentWave")
    private int currentWave;

    @Property("createdAt")
    private Instant createdAt;

    @Version
    private Long version;




}
