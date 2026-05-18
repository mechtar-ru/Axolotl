package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Node(labels = {"Package", "Code"})
public class CodePackage {
    
    @Id
    @GeneratedValue
    private Long id;
    
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
    
    public CodePackage() {}
    
    public CodePackage(String name, String path) {
        this.name = name;
        this.path = path;
    }
    
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Set<CodePackage> getSubpackages() { return subpackages; }
    public void setSubpackages(Set<CodePackage> subpackages) { this.subpackages = subpackages; }
    public Set<CodeClass> getClasses() { return classes; }
    public void setClasses(Set<CodeClass> classes) { this.classes = classes; }
    public CodePackage getParent() { return parent; }
    public void setParent(CodePackage parent) { this.parent = parent; }
}