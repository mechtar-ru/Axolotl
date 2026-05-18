package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Node(labels = {"Decision", "Architecture"})
public class Decision {
    
    @Id
    @GeneratedValue
    private Long id;
    
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
    
    public Decision() {}
    
    public Decision(String title, String description, String rationale) {
        this.title = title;
        this.description = description;
        this.rationale = rationale;
    }
    
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Set<CodeClass> getImpactedClasses() { return impactedClasses; }
    public void setImpactedClasses(Set<CodeClass> impactedClasses) { this.impactedClasses = impactedClasses; }
    public Set<CodeMethod> getImpactedMethods() { return impactedMethods; }
    public void setImpactedMethods(Set<CodeMethod> impactedMethods) { this.impactedMethods = impactedMethods; }
    public Set<Decision> getRelatedDecisions() { return relatedDecisions; }
    public void setRelatedDecisions(Set<Decision> relatedDecisions) { this.relatedDecisions = relatedDecisions; }
}