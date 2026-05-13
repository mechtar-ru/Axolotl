# Neo4j `id()` → `elementId()` Migration — Implementation Plan

**Goal:** Eliminate deprecated `id()` Neo4j warnings by migrating 5 code graph entities from `Long @Id @GeneratedValue` to `String @Id` (UUID-based), and updating 5 corresponding repositories from `Neo4jRepository<T, Long>` to `Neo4jRepository<T, String>`.

**Architecture:** All 5 entities share the exact same pattern — replace `@GeneratedValue Long id` with `String id`, assign UUID in constructors, update getter/setter types. All 5 repositories replace `Long` generic with `String`. Custom `@Query` methods use domain properties only (not `id()`), so they're unaffected. No services reference Long IDs for these entities — zero downstream impact.

**Design:** `thoughts/shared/designs/2026-05-13-neo4j-elementId-migration.md`

### Key decisions (design gaps filled)

| Gap | Decision |
|-----|----------|
| Import management | `@Id` comes from the same wildcard `import org.springframework.data.neo4j.core.schema.*;` — no import change needed (we just stop using `@GeneratedValue` which is in the same package but we never import it explicitly) |
| `@GeneratedValue` removal | Simply delete the `@GeneratedValue` annotation line — `@Id` alone is sufficient since we manage the ID ourselves |
| UUID import location | Add `import java.util.UUID;` at the top of each entity file, grouped with other JDK imports |
| Constructor assignment | `this.id = UUID.randomUUID().toString();` as the **first line** of each constructor body (before any field assignments) |
| Wildcard import `org.springframework.data.neo4j.core.schema.*` | Keep it unchanged — it's already a wildcard import and `@Id` / `@Relationship` / `@Property` / `@Node` all come from it |

---

## Dependency Graph

```
Batch 1 (parallel — 5 implementers): 1.1, 1.2, 1.3, 1.4, 1.5 [entity files — no deps]
Batch 2 (parallel — 5 implementers): 2.1, 2.2, 2.3, 2.4, 2.5 [repository files — depends on Batch 1 compiling]
Batch 3 (sequential):                 3.1, 3.2               [compile verify, test verify]
```

---

## Batch 1: Entity Migrations (parallel — 5 implementers)

All tasks in this batch have NO dependencies and run simultaneously.

### Task 1.1: CodeClass.java — Long → String ID
**File:** `backend/src/main/java/com/agent/orchestrator/graph/model/CodeClass.java`
**Test:** none (no behavior change, verify via compile + existing tests)
**Depends:** none

**Changes (4 edits):**

**Edit 1:** Replace `@GeneratedValue Long id` field — remove `@GeneratedValue`, change type to `String`:

- **Lines:** 11-13
- **Old:**
```java
    @Id
    @GeneratedValue
    private Long id;
```
- **New:**
```java
    @Id
    private String id;
```

**Edit 2:** Add UUID generation to both constructors:

- **Lines:** 69, 71 (constructors)
```java
    public CodeClass() {}

    public CodeClass(String name, String qualifiedName, String packageName) {
        this.name = name;
        this.qualifiedName = qualifiedName;
        this.packageName = packageName;
    }
```
- **New:**
```java
    public CodeClass() {
        this.id = UUID.randomUUID().toString();
    }

    public CodeClass(String name, String qualifiedName, String packageName) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.qualifiedName = qualifiedName;
        this.packageName = packageName;
    }
```

**Edit 3:** Update getter/setter types:

- **Lines:** 77-78
- **Old:**
```java
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
```
- **New:**
```java
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
```

**Edit 4:** Add UUID import after line 5 (group with JDK imports):

- **Old:**
```java
import java.util.HashSet;
import java.util.Set;
```
- **New:**
```java
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
```

**Full edited file** (for copy-paste verification):

```java
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
```

**Verify:** `mvn compile -q` (or full Batch 3 later)
**Commit:** `feat(graph): migrate CodeClass to UUID-based String @Id`

---

### Task 1.2: CodeMethod.java — Long → String ID
**File:** `backend/src/main/java/com/agent/orchestrator/graph/model/CodeMethod.java`
**Test:** none
**Depends:** none

**Changes (4 edits):**

**Edit 1:** Replace `@GeneratedValue Long id` field:

- **Lines:** 11-13
- **Old:**
```java
    @Id
    @GeneratedValue
    private Long id;
```
- **New:**
```java
    @Id
    private String id;
```

**Edit 2:** Add UUID generation to constructors (lines 66, 68):

- **Old:**
```java
    public CodeMethod() {}

    public CodeMethod(String name, String signature, String returnType) {
        this.name = name;
        this.signature = signature;
        this.returnType = returnType;
    }
```
- **New:**
```java
    public CodeMethod() {
        this.id = UUID.randomUUID().toString();
    }

    public CodeMethod(String name, String signature, String returnType) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.signature = signature;
        this.returnType = returnType;
    }
```

**Edit 3:** Update getter/setter types (lines 74-75):

- **Old:**
```java
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
```
- **New:**
```java
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
```

**Edit 4:** Add UUID import:

- **Old:**
```java
import java.util.HashSet;
import java.util.Set;
```
- **New:**
```java
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
```

**Full edited file** (for copy-paste verification):

```java
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
```

**Commit:** `feat(graph): migrate CodeMethod to UUID-based String @Id`

---

### Task 1.3: CodeField.java — Long → String ID
**File:** `backend/src/main/java/com/agent/orchestrator/graph/model/CodeField.java`
**Test:** none
**Depends:** none

**Changes (4 edits):**

**Edit 1:** Replace `@GeneratedValue Long id` field (lines 9-11):

- **Old:**
```java
    @Id
    @GeneratedValue
    private Long id;
```
- **New:**
```java
    @Id
    private String id;
```

**Edit 2:** Add UUID generation to constructors (lines 46, 48):

- **Old:**
```java
    public CodeField() {}

    public CodeField(String name, String type) {
        this.name = name;
        this.type = type;
    }
```
- **New:**
```java
    public CodeField() {
        this.id = UUID.randomUUID().toString();
    }

    public CodeField(String name, String type) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.type = type;
    }
```

**Edit 3:** Update getter/setter (lines 53-54):

- **Old:**
```java
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
```
- **New:**
```java
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
```

**Edit 4:** Add UUID import (after line 5, grouping with JDK imports):

- **Old:**
```java
import org.springframework.data.neo4j.core.schema.*;
import java.time.Instant;
```
- **New:**
```java
import org.springframework.data.neo4j.core.schema.*;
import java.time.Instant;
import java.util.UUID;
```

**Full edited file:**

```java
package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;
import java.time.Instant;
import java.util.UUID;

@Node(labels = {"Field", "Code"})
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

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getHash() { return hash; }
    public void setHash(String hash) { this.hash = hash; }
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public boolean isStatic() { return isStatic; }
    public void setStatic(boolean isStatic) { this.isStatic = isStatic; }
    public boolean isFinal() { return isFinal; }
    public void setFinal(boolean isFinal) { this.isFinal = isFinal; }
    public String getInitializer() { return initializer; }
    public void setInitializer(String initializer) { this.initializer = initializer; }
    public int getLineNumber() { return lineNumber; }
    public void setLineNumber(int lineNumber) { this.lineNumber = lineNumber; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public CodeClass getParentClass() { return parentClass; }
    public void setParentClass(CodeClass parentClass) { this.parentClass = parentClass; }
}
```

**Commit:** `feat(graph): migrate CodeField to UUID-based String @Id`

---

### Task 1.4: CodePackage.java — Long → String ID
**File:** `backend/src/main/java/com/agent/orchestrator/graph/model/CodePackage.java`
**Test:** none
**Depends:** none

**Changes (4 edits):**

**Edit 1:** Replace `@GeneratedValue Long id` field (lines 11-13):

- **Old:**
```java
    @Id
    @GeneratedValue
    private Long id;
```
- **New:**
```java
    @Id
    private String id;
```

**Edit 2:** Add UUID generation to constructors (lines 36, 38):

- **Old:**
```java
    public CodePackage() {}

    public CodePackage(String name, String path) {
        this.name = name;
        this.path = path;
    }
```
- **New:**
```java
    public CodePackage() {
        this.id = UUID.randomUUID().toString();
    }

    public CodePackage(String name, String path) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.path = path;
    }
```

**Edit 3:** Update getter/setter (lines 43-44):

- **Old:**
```java
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
```
- **New:**
```java
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
```

**Edit 4:** Add UUID import (after line 4, grouping JDK imports):

- **Old:**
```java
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
```
- **New:**
```java
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
```

**Full edited file:**

```java
package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Node(labels = {"Package", "Code"})
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
    
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
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
```

**Commit:** `feat(graph): migrate CodePackage to UUID-based String @Id`

---

### Task 1.5: Decision.java — Long → String ID
**File:** `backend/src/main/java/com/agent/orchestrator/graph/model/Decision.java`
**Test:** none
**Depends:** none

**Changes (4 edits):**

**Edit 1:** Replace `@GeneratedValue Long id` field (lines 11-13):

- **Old:**
```java
    @Id
    @GeneratedValue
    private Long id;
```
- **New:**
```java
    @Id
    private String id;
```

**Edit 2:** Add UUID generation to constructors (lines 51, 53):

- **Old:**
```java
    public Decision() {}

    public Decision(String title, String description, String rationale) {
        this.title = title;
        this.description = description;
        this.rationale = rationale;
    }
```
- **New:**
```java
    public Decision() {
        this.id = UUID.randomUUID().toString();
    }

    public Decision(String title, String description, String rationale) {
        this.id = UUID.randomUUID().toString();
        this.title = title;
        this.description = description;
        this.rationale = rationale;
    }
```

**Edit 3:** Update getter/setter (lines 59-60):

- **Old:**
```java
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
```
- **New:**
```java
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
```

**Edit 4:** Add UUID import (after line 4, grouping JDK imports):

- **Old:**
```java
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
```
- **New:**
```java
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
```

**Full edited file:**

```java
package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Node(labels = {"Decision", "Architecture"})
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
    
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
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
```

**Commit:** `feat(graph): migrate Decision to UUID-based String @Id`

---

## Batch 2: Repository Migrations (parallel — 5 implementers)

All tasks in this batch depend on Batch 1 compiling, but run in parallel with each other.

### Task 2.1: CodeClassRepository — `Long` → `String`
**File:** `backend/src/main/java/com/agent/orchestrator/graph/repository/CodeClassRepository.java`
**Test:** none (verify via compile + existing tests)
**Depends:** 1.1

**Edit:** Change `Long` to `String` in `extends` clause (line 13):

- **Old:**
```java
public interface CodeClassRepository extends Neo4jRepository<CodeClass, Long> {
```
- **New:**
```java
public interface CodeClassRepository extends Neo4jRepository<CodeClass, String> {
```

**Full edited file:**

```java
package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.CodeClass;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CodeClassRepository extends Neo4jRepository<CodeClass, String> {
    
    Optional<CodeClass> findByQualifiedName(String qualifiedName);

    Optional<CodeClass> findByHash(String hash);
    
    List<CodeClass> findByNameContaining(String nameFragment);
    
    List<CodeClass> findByPackageName(String packageName);
    
    @Query("""
        MATCH (c:Class)-[r:DEPENDS_ON]->(dep:Class)
        WHERE c.qualifiedName = $qualifiedName
        RETURN dep ORDER BY dep.name
        """)
    List<CodeClass> findDependencies(@Param("qualifiedName") String qualifiedName);
    
    @Query("""
        MATCH (d:Decision)-[:AFFECTS]->(c:Class)
        WHERE d.status = 'accepted'
        RETURN c ORDER BY d.decidedAt DESC LIMIT $limit
        """)
    List<CodeClass> findRecentlyImpactedClasses(@Param("limit") int limit);
    
    @Query("""
        MATCH (c:Class)
        WHERE c.name CONTAINS $keyword OR c.qualifiedName CONTAINS $keyword
        RETURN c ORDER BY c.name LIMIT $limit
        """)
    List<CodeClass> search(@Param("keyword") String keyword, @Param("limit") int limit);

    @Query("MATCH (c:Class) WHERE c.imports CONTAINS $importPattern RETURN c LIMIT 20")
    List<CodeClass> findByImportsContaining(@Param("importPattern") String importPattern);

    @Query("MATCH (c:Class) WHERE c.qualifiedName CONTAINS $qualifiedName RETURN c")
    List<CodeClass> findByQualifiedNameContaining(@Param("qualifiedName") String qualifiedName);

    List<CodeClass> findByNameContainingOrQualifiedNameContaining(String name, String qualifiedName);
}
```

**Verify:** `mvn compile -q`
**Commit:** `feat(graph): update CodeClassRepository generic type to String`

---

### Task 2.2: CodeMethodRepository — `Long` → `String`
**File:** `backend/src/main/java/com/agent/orchestrator/graph/repository/CodeMethodRepository.java`
**Test:** none
**Depends:** 1.2

**Edit:** Change `Long` to `String` in `extends` clause (line 13):

- **Old:**
```java
public interface CodeMethodRepository extends Neo4jRepository<CodeMethod, Long> {
```
- **New:**
```java
public interface CodeMethodRepository extends Neo4jRepository<CodeMethod, String> {
```

**Full edited file:**

```java
package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.CodeMethod;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CodeMethodRepository extends Neo4jRepository<CodeMethod, String> {
    
    Optional<CodeMethod> findBySignature(String signature);
    
    List<CodeMethod> findByNameContaining(String nameFragment);
    
    @Query("""
        MATCH (c:Class)-[:DECLARES]->(m:Method)
        WHERE c.qualifiedName = $classQualifiedName
        RETURN m ORDER BY m.lineNumber
        """)
    List<CodeMethod> findByClass(@Param("classQualifiedName") String classQualifiedName);
    
    @Query("""
        MATCH (m:Method)-[:CALLS]->(called:Method)
        WHERE m.signature = $callerSignature
        RETURN called
        """)
    List<CodeMethod> findCallees(@Param("callerSignature") String callerSignature);
    
    @Query("""
        MATCH (m:Method)<-[:CALLS]-(caller:Method)
        WHERE m.signature = $calleeSignature
        RETURN caller
        """)
    List<CodeMethod> findCallers(@Param("calleeSignature") String calleeSignature);
    
    @Query("""
        MATCH (m:Method)
        WHERE m.name CONTAINS $keyword OR m.signature CONTAINS $keyword
        RETURN m ORDER BY m.name LIMIT $limit
        """)
    List<CodeMethod> search(@Param("keyword") String keyword, @Param("limit") int limit);

    @Query("MATCH (m:Method) WHERE m.body CONTAINS $pattern RETURN m LIMIT 50")
    List<CodeMethod> findByBodyContaining(@Param("pattern") String pattern);

    @Query("MATCH (m:Method) WHERE m.returnType = $returnType RETURN m LIMIT 50")
    List<CodeMethod> findByReturnType(@Param("returnType") String returnType);

    @Query("MATCH (m:Method) WHERE m.description CONTAINS $desc RETURN m LIMIT 50")
    List<CodeMethod> findByDescriptionContaining(@Param("desc") String desc);
}
```

**Verify:** `mvn compile -q`
**Commit:** `feat(graph): update CodeMethodRepository generic type to String`

---

### Task 2.3: CodeFieldRepository — `Long` → `String`
**File:** `backend/src/main/java/com/agent/orchestrator/graph/repository/CodeFieldRepository.java`
**Test:** none
**Depends:** 1.3

**Edit:** Change `Long` to `String` in `extends` clause (line 13):

- **Old:**
```java
public interface CodeFieldRepository extends Neo4jRepository<CodeField, Long> {
```
- **New:**
```java
public interface CodeFieldRepository extends Neo4jRepository<CodeField, String> {
```

**Full edited file:**

```java
package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.CodeField;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CodeFieldRepository extends Neo4jRepository<CodeField, String> {

    @Query("MATCH (f:Field {signature: $signature}) RETURN f")
    Optional<CodeField> findBySignature(@Param("signature") String signature);

    @Query("MATCH (f:Field {hash: $hash}) RETURN f")
    Optional<CodeField> findByHash(@Param("hash") String hash);

    @Query("MATCH (c:Class {qualifiedName: $className})-[:DECLARES]->(f:Field) RETURN f")
    List<CodeField> findByClassName(@Param("className") String className);
}
```

**Verify:** `mvn compile -q`
**Commit:** `feat(graph): update CodeFieldRepository generic type to String`

---

### Task 2.4: CodePackageRepository — `Long` → `String`
**File:** `backend/src/main/java/com/agent/orchestrator/graph/repository/CodePackageRepository.java`
**Test:** none
**Depends:** 1.4

**Edit:** Change `Long` to `String` in `extends` clause (line 13):

- **Old:**
```java
public interface CodePackageRepository extends Neo4jRepository<CodePackage, Long> {
```
- **New:**
```java
public interface CodePackageRepository extends Neo4jRepository<CodePackage, String> {
```

**Full edited file:**

```java
package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.CodePackage;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CodePackageRepository extends Neo4jRepository<CodePackage, String> {
    
    Optional<CodePackage> findByPath(String path);
    
    List<CodePackage> findByNameContaining(String nameFragment);
    
    @Query("MATCH (p:Package) WHERE NOT EXISTS((:Package)-[:CONTAINS]->(p)) RETURN p")
    List<CodePackage> findRootPackages();
}
```

**Verify:** `mvn compile -q`
**Commit:** `feat(graph): update CodePackageRepository generic type to String`

---

### Task 2.5: DecisionRepository — `Long` → `String`
**File:** `backend/src/main/java/com/agent/orchestrator/graph/repository/DecisionRepository.java`
**Test:** none
**Depends:** 1.5

**Edit:** Change `Long` to `String` in `extends` clause (line 14):

- **Old:**
```java
public interface DecisionRepository extends Neo4jRepository<Decision, Long> {
```
- **New:**
```java
public interface DecisionRepository extends Neo4jRepository<Decision, String> {
```

**Full edited file:**

```java
package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.Decision;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface DecisionRepository extends Neo4jRepository<Decision, String> {
    
    List<Decision> findByTitleContaining(String titleFragment);
    
    List<Decision> findByStatus(String status);
    
    List<Decision> findByPriority(String priority);
    
    @Query("""
        MATCH (d:Decision)
        WHERE d.status = $status AND d.decidedAt >= $since
        RETURN d ORDER BY d.decidedAt DESC
        """)
    List<Decision> findByStatusSince(@Param("status") String status, @Param("since") Instant since);
    
    @Query("""
        MATCH (d:Decision)-[:AFFECTS]->(c:Class)
        WHERE c.qualifiedName = $classQualifiedName
        RETURN d ORDER BY d.decidedAt DESC
        """)
    List<Decision> findByAffectedClass(@Param("classQualifiedName") String classQualifiedName);
    
    @Query("""
        MATCH (d:Decision)
        WHERE d.title CONTAINS $keyword OR d.description CONTAINS $keyword
           OR d.rationale CONTAINS $keyword
        RETURN d ORDER BY d.createdAt DESC LIMIT $limit
        """)
    List<Decision> search(@Param("keyword") String keyword, @Param("limit") int limit);
}
```

**Verify:** `mvn compile -q`
**Commit:** `feat(graph): update DecisionRepository generic type to String`

---

## Batch 3: Verification (sequential — 1 implementer)

### Task 3.1: Compile verification
**File:** none — run `mvn compile -q` in `backend/`
**Depends:** 2.1, 2.2, 2.3, 2.4, 2.5

**Command:** `cd backend && mvn compile -q`

**Expected result:** BUILD SUCCESS. If compilation errors occur, the most likely cause is a service that references `Long getId()` or uses `Neo4jRepository<T, Long>` generics elsewhere. Check:
- `grep -rn "Neo4jRepository<.*, Long>" backend/src/` — should return no results for the 5 migrated repos
- `grep -rn "private Long id" backend/src/main/java/com/agent/orchestrator/graph/model/` — should return no results

**Commit:** none (verification only)

### Task 3.2: Test verification
**File:** none — run `mvn test -q` in `backend/`
**Depends:** 3.1

**Command:** `cd backend && mvn test -q`

**Expected result:** All tests pass. Existing unit tests for repositories (findByHash, findByQualifiedName, etc.) exercise the repository methods which use domain properties — they should continue to pass because no query logic changed.

Additionally verify:
```bash
# Confirm no id() usage in @Query annotations (should already be clean)
grep -rn "id()" backend/src/main/java/com/agent/orchestrator/graph/
# Expected: no output (0 matches)

# Confirm no Long references remain in graph model/repository
grep -rn "Long" backend/src/main/java/com/agent/orchestrator/graph/
# Expected: only Long-like patterns in unrelated code (e.g., timestamps, int/Integer types)
```

**Commit:** none (verification only)

---

## Rollback instructions

If compilation or tests fail after Batch 2:

1. **Entity rollback:** Revert each entity file with:
   ```bash
   git checkout -- backend/src/main/java/com/agent/orchestrator/graph/model/CodeClass.java
   git checkout -- backend/src/main/java/com/agent/orchestrator/graph/model/CodeMethod.java
   git checkout -- backend/src/main/java/com/agent/orchestrator/graph/model/CodeField.java
   git checkout -- backend/src/main/java/com/agent/orchestrator/graph/model/CodePackage.java
   git checkout -- backend/src/main/java/com/agent/orchestrator/graph/model/Decision.java
   ```

2. **Repository rollback:**
   ```bash
   git checkout -- backend/src/main/java/com/agent/orchestrator/graph/repository/CodeClassRepository.java
   git checkout -- backend/src/main/java/com/agent/orchestrator/graph/repository/CodeMethodRepository.java
   git checkout -- backend/src/main/java/com/agent/orchestrator/graph/repository/CodeFieldRepository.java
   git checkout -- backend/src/main/java/com/agent/orchestrator/graph/repository/CodePackageRepository.java
   git checkout -- backend/src/main/java/com/agent/orchestrator/graph/repository/DecisionRepository.java
   ```

---

## Task Summary

| # | File | Change | Dependencies |
|---|------|--------|-------------|
| 1.1 | `graph/model/CodeClass.java` | Remove `@GeneratedValue`, `Long id` → `String id`, add UUID in constructors, update getter/setter types, add UUID import | none |
| 1.2 | `graph/model/CodeMethod.java` | Same pattern | none |
| 1.3 | `graph/model/CodeField.java` | Same pattern | none |
| 1.4 | `graph/model/CodePackage.java` | Same pattern | none |
| 1.5 | `graph/model/Decision.java` | Same pattern | none |
| 2.1 | `graph/repository/CodeClassRepository.java` | `Neo4jRepository<CodeClass, Long>` → `Neo4jRepository<CodeClass, String>` | 1.1 |
| 2.2 | `graph/repository/CodeMethodRepository.java` | `Neo4jRepository<CodeMethod, Long>` → `Neo4jRepository<CodeMethod, String>` | 1.2 |
| 2.3 | `graph/repository/CodeFieldRepository.java` | `Neo4jRepository<CodeField, Long>` → `Neo4jRepository<CodeField, String>` | 1.3 |
| 2.4 | `graph/repository/CodePackageRepository.java` | `Neo4jRepository<CodePackage, Long>` → `Neo4jRepository<CodePackage, String>` | 1.4 |
| 2.5 | `graph/repository/DecisionRepository.java` | `Neo4jRepository<Decision, Long>` → `Neo4jRepository<Decision, String>` | 1.5 |
| 3.1 | — | `mvn compile -q` — verify compilation | 2.1-2.5 |
| 3.2 | — | `mvn test -q` — run unit tests | 3.1 |

**Total: 10 modified files, 2 verification steps, 3 batches, max 5 parallel implementers.**
