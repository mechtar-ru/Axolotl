package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

@Node("PlanStep")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class GraphPlanStep {

    @Id
    @Property("id")
    private String id;

    @Property("stepId")
    private int stepId;

    @Property("title")
    private String title;

    @Property("description")
    private String description;

    @Property("status")
    private String status = "PENDING";

    @Property("schemaId")
    private String schemaId;

    @Property("planId")
    private String planId;

    @Property("reason")
    private String reason;

    @Property("createdAt")
    private String createdAt;

    @Property("updatedAt")
    private String updatedAt;

    @Relationship(type = "DEPENDS_ON", direction = Relationship.Direction.OUTGOING)
    private List<GraphPlanStep> dependsOn = new ArrayList<>();

    @Relationship(type = "DEPENDS_ON", direction = Relationship.Direction.INCOMING)
    private List<GraphPlanStep> dependedBy = new ArrayList<>();












    public List<GraphPlanStep> getDependsOn() { return dependsOn; }

    public List<GraphPlanStep> getDependedBy() { return dependedBy; }
}
