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

@Node(labels = {"Class", "Code"})
@Getter
@Setter
@ToString
public class CodeClass {

    @Id
    private String id;

    @Property(name = "name")
    private String name;

    @Property(name = "qualifiedName")
    private String qualifiedName;

    @Property(name = "hash")
    private String hash;

    @Property(name = "package")
    private String packageName;

    @Property(name = "filePath")
    private String filePath;

    @Property(name = "isAbstract")
    private boolean isAbstract = false;

    @Property(name = "isInterface")
    private boolean isInterface = false;

    @Property(name = "visibility")
    private String visibility = "public";

    @Property(name = "astBody")
    private String astBody;

    @Property(name = "description")
    private String description;

    @Property(name = "imports")
    private String imports;

    @Property(name = "createdAt")
    private Instant createdAt = Instant.now();

    @Property(name = "updatedAt")
    private Instant updatedAt = Instant.now();

    @Property(name = "tokenCount")
    private int tokenCount = 0;

    @Relationship(type = "DECLARES", direction = Relationship.Direction.OUTGOING)
    private Set<CodeMethod> methods = new HashSet<>();

    @Relationship(type = "DECLARES", direction = Relationship.Direction.OUTGOING)
    private Set<CodeField> fields = new HashSet<>();

    @Relationship(type = "EXTENDS", direction = Relationship.Direction.OUTGOING)
    private CodeClass superClass;

    @Relationship(type = "IMPLEMENTS", direction = Relationship.Direction.OUTGOING)
    private Set<CodeClass> interfaces = new HashSet<>();

    @Relationship(type = "DEPENDS_ON", direction = Relationship.Direction.OUTGOING)
    private Set<CodeClass> dependencies = new HashSet<>();

    @Relationship(type = "CONTAINS", direction = Relationship.Direction.INCOMING)
    private CodePackage parentPackage;

    public CodeClass() {
        this.id = UUID.randomUUID().toString();
    }

    public CodeClass(String name, String qualifiedName, String packageName) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.qualifiedName = qualifiedName;
        this.packageName = packageName;
    }

    public boolean isAbstract() { return isAbstract; }
    public boolean isInterface() { return isInterface; }
    public Set<CodeMethod> getMethods() { return methods; }
    public Set<CodeField> getFields() { return fields; }
    public Set<CodeClass> getInterfaces() { return interfaces; }
    public Set<CodeClass> getDependencies() { return dependencies; }
}
