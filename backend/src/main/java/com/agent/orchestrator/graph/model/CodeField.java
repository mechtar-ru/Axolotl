package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

@Node(labels = {"Field", "Code"})
@Getter
@Setter
@ToString
public class CodeField {

    @Id
    private String id;

    @Property(name = "name")
    private String name;

    @Property(name = "type")
    private String type;

    @Property(name = "hash")
    private String hash;

    @Property(name = "signature")
    private String signature;

    @Property(name = "visibility")
    private String visibility = "private";

    @Property(name = "isStatic")
    private boolean isStatic = false;

    @Property(name = "isFinal")
    private boolean isFinal = false;

    @Property(name = "initializer")
    private String initializer;

    @Property(name = "lineNumber")
    private int lineNumber;

    @Property(name = "createdAt")
    private Instant createdAt = Instant.now();

    @Relationship(type = "DECLARES", direction = Relationship.Direction.INCOMING)
    private CodeClass parentClass;

    public CodeField() {
        this.id = UUID.randomUUID().toString();
    }

    public CodeField(String name, String type) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.type = type;
    }

    public boolean isStatic() { return isStatic; }
    public boolean isFinal() { return isFinal; }
}
