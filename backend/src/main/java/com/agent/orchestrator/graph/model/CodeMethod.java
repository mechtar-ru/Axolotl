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

@Node(labels = {"Method", "Code"})
@Getter
@Setter
@ToString
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

    public boolean isStatic() { return isStatic; }
    public boolean isAbstract() { return isAbstract; }
    public Set<CodeMethod> getCalls() { return calls; }
    public Set<CodeMethod> getCalledBy() { return calledBy; }
}
