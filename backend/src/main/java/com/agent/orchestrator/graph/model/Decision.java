package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

@Node(labels = {"Decision", "Architecture"})
@Getter
@Setter
@ToString
public class Decision {
    
    @Id
    private String id;
    
    @Property(name = "title")
    private String title;
    
    @Property(name = "description")
    private String description;
    
    @Property(name = "rationale")
    private String rationale;
    
    @Property(name = "status")
    private String status = "proposed";
    
    @Property(name = "priority")
    private String priority = "MEDIUM";
    
    @Property(name = "decidedBy")
    private String decidedBy;
    
    @Property(name = "createdAt")
    private Instant createdAt = Instant.now();
    
    @Property(name = "decidedAt")
    private Instant decidedAt;
    
    @Property(name = "source")
    private String source;
    
    @Relationship(type = "AFFECTS", direction = Relationship.Direction.OUTGOING)
    private Set<CodeClass> impactedClasses = new HashSet<>();
    
    @Relationship(type = "AFFECTS", direction = Relationship.Direction.OUTGOING)
    private Set<CodeMethod> impactedMethods = new HashSet<>();
    
    @Relationship(type = "RELATED_TO", direction = Relationship.Direction.OUTGOING)
    private Set<Decision> relatedDecisions = new HashSet<>();
    
    public Decision() {
        this.id = UUID.randomUUID().toString();
    }
    
    public Decision(String title, String description, String rationale) {
        this.id = UUID.randomUUID().toString();
        this.title = title;
        this.description = description;
        this.rationale = rationale;
    }
    
    public Set<CodeClass> getImpactedClasses() { return impactedClasses; }
    public Set<CodeMethod> getImpactedMethods() { return impactedMethods; }
    public Set<Decision> getRelatedDecisions() { return relatedDecisions; }
}
