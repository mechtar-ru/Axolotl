package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Node(labels = {"Method", "Code"})
public class CodeMethod {

    @Id
    private String id;

    @Property(name = "name")
    private String name;

    @Property(name = "signature")
    private String signature;

    @Property(name = "hash")
    private String hash;

    @Property(name = "returnType")
    private String returnType;

    @Property(name = "visibility")
    private String visibility = "public";

    @Property(name = "isStatic")
    private boolean isStatic = false;

    @Property(name = "isAbstract")
    private boolean isAbstract = false;

    @Property(name = "lineNumber")
    private int lineNumber;

    @Property(name = "parameters")
    private String parameters;

    @Property(name = "body")
    private String body;

    @Property(name = "description")
    private String description;

    @Property(name = "tokenCount")
    private int tokenCount = 0;

    @Property(name = "createdAt")
    private Instant createdAt = Instant.now();

    @Property(name = "updatedAt")
    private Instant updatedAt = Instant.now();

    @Relationship(type = "CALLS", direction = Relationship.Direction.OUTGOING)
    private Set<CodeMethod> calls = new HashSet<>();

    @Relationship(type = "CALLS", direction = Relationship.Direction.INCOMING)
    private Set<CodeMethod> calledBy = new HashSet<>();

    @Relationship(type = "DECLARES", direction = Relationship.Direction.INCOMING)
    private CodeClass parentClass;

    public CodeMethod() {
        this.id = UUID.randomUUID().toString();
    }

    public CodeMethod(String name, String signature, String returnType) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.signature = signature;
        this.returnType = returnType;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
    public String getHash() { return hash; }
    public void setHash(String hash) { this.hash = hash; }
    public String getReturnType() { return returnType; }
    public void setReturnType(String returnType) { this.returnType = returnType; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public boolean isStatic() { return isStatic; }
    public void setStatic(boolean isStatic) { this.isStatic = isStatic; }
    public boolean isAbstract() { return isAbstract; }
    public void setAbstract(boolean isAbstract) { this.isAbstract = isAbstract; }
    public int getLineNumber() { return lineNumber; }
    public void setLineNumber(int lineNumber) { this.lineNumber = lineNumber; }
    public String getParameters() { return parameters; }
    public void setParameters(String parameters) { this.parameters = parameters; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getTokenCount() { return tokenCount; }
    public void setTokenCount(int tokenCount) { this.tokenCount = tokenCount; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Set<CodeMethod> getCalls() { return calls; }
    public void setCalls(Set<CodeMethod> calls) { this.calls = calls; }
    public Set<CodeMethod> getCalledBy() { return calledBy; }
    public void setCalledBy(Set<CodeMethod> calledBy) { this.calledBy = calledBy; }
    public CodeClass getParentClass() { return parentClass; }
    public void setParentClass(CodeClass parentClass) { this.parentClass = parentClass; }
}
