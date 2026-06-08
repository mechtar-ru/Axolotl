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

@Node(labels = {"Package", "Code"})
@Getter
@Setter
@ToString
public class CodePackage {
    
    @Id
    private String id;
    
    @Property(name = "name")
    private String name;
    
    @Property(name = "path")
    private String path;
    
    @Property(name = "language")
    private String language = "java";
    
    @Property(name = "createdAt")
    private Instant createdAt = Instant.now();
    
    @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
    private Set<CodePackage> subpackages = new HashSet<>();
    
    @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
    private Set<CodeClass> classes = new HashSet<>();
    
    @Relationship(type = "CONTAINS", direction = Relationship.Direction.INCOMING)
    private CodePackage parent;
    
    public CodePackage() {
        this.id = UUID.randomUUID().toString();
    }
    
    public CodePackage(String name, String path) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.path = path;
    }
    
    public Set<CodePackage> getSubpackages() { return subpackages; }
    public Set<CodeClass> getClasses() { return classes; }
}