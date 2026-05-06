package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;
import java.time.Instant;

@Node(labels = {"Field", "Code"})
public class CodeField {
    
    @Id
    @GeneratedValue
    private Long id;
    
    @Property(name = "name")
    private String name;
    
    @Property(name = "type")
    private String type;
    
    @Property(name = "visibility")
    private String visibility = "private";
    
    @Property(name = "isStatic")
    private boolean isStatic = false;
    
    @Property(name = "isFinal")
    private boolean isFinal = false;
    
    @Property(name = "createdAt")
    private Instant createdAt = Instant.now();
    
    @Relationship(type = "DECLARES", direction = Relationship.Direction.INCOMING)
    private CodeClass parentClass;
    
    public CodeField() {}
    
    public CodeField(String name, String type) {
        this.name = name;
        this.type = type;
    }
    
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public boolean isStatic() { return isStatic; }
    public void setStatic(boolean isStatic) { this.isStatic = isStatic; }
    public boolean isFinal() { return isFinal; }
    public void setFinal(boolean isFinal) { this.isFinal = isFinal; }
    public Instant getCreatedAt() { return createdAt; }
    public CodeClass getParentClass() { return parentClass; }
    public void setParentClass(CodeClass parentClass) { this.parentClass = parentClass; }
}