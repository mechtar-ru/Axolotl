package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Node(labels = {"Class", "Code"})
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

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getQualifiedName() { return qualifiedName; }
    public void setQualifiedName(String qualifiedName) { this.qualifiedName = qualifiedName; }
    public String getHash() { return hash; }
    public void setHash(String hash) { this.hash = hash; }
    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public boolean isAbstract() { return isAbstract; }
    public void setAbstract(boolean isAbstract) { this.isAbstract = isAbstract; }
    public boolean isInterface() { return isInterface; }
    public void setInterface(boolean isInterface) { this.isInterface = isInterface; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public String getAstBody() { return astBody; }
    public void setAstBody(String astBody) { this.astBody = astBody; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getImports() { return imports; }
    public void setImports(String imports) { this.imports = imports; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public int getTokenCount() { return tokenCount; }
    public void setTokenCount(int tokenCount) { this.tokenCount = tokenCount; }
    public Set<CodeMethod> getMethods() { return methods; }
    public void setMethods(Set<CodeMethod> methods) { this.methods = methods; }
    public Set<CodeField> getFields() { return fields; }
    public void setFields(Set<CodeField> fields) { this.fields = fields; }
    public CodeClass getSuperClass() { return superClass; }
    public void setSuperClass(CodeClass superClass) { this.superClass = superClass; }
    public Set<CodeClass> getInterfaces() { return interfaces; }
    public void setInterfaces(Set<CodeClass> interfaces) { this.interfaces = interfaces; }
    public Set<CodeClass> getDependencies() { return dependencies; }
    public void setDependencies(Set<CodeClass> dependencies) { this.dependencies = dependencies; }
    public CodePackage getParentPackage() { return parentPackage; }
    public void setParentPackage(CodePackage parentPackage) { this.parentPackage = parentPackage; }
}
