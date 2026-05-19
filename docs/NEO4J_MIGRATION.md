# Axolotl Neo4j Migration Specification

## Overview

Replace MemPalace (ChromaDB) with Neo4j (Community Edition) for unified graph storage of:
- Codebase structure (packages, classes, methods, dependencies)
- Architectural decisions from agent dialogues
- Projects, schemas, execution results
- Agent instructions and rules

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Axolotl                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│  Vue 3 Frontend ──► Spring Boot Backend ──► Neo4j (Community Edition)    │
│       │                    │                              │               │
│       │                                                    │               │
│       │                                               │               │
│       │                    │                              │               │
│       │              MCP Server ◄────── (agents)       │               │
│       │                                                 │               │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Graph Schema

### Node Types

| Node | Labels | Properties | Description |
|------|--------|------------|-------------|
| Package | `:Package`, `:Code` | `name`, `path`, `language`, `createdAt` | Java/OS package |
| Class | `:Class`, `:Code` | `name`, `qualifiedName`, `package`, `filePath`, `isAbstract`, `isInterface` | Java class |
| Method | `:Method`, `:Code` | `name`, `signature`, `returnType`, `visibility`, `isStatic`, `lineNumber` | Class method |
| Field | `:Field`, `:Code` | `name`, `type`, `visibility`, `isStatic`, `isFinal` | Class field |
| Import | `:Import`, `:Code` | `qualifiedName`, `isStatic`, `isWildcard` | Import statement |
| Decision | `:Decision`, `:Architecture` | `title`, `description`, `rationale`, `status`, `createdAt`, `decidedBy` | Architectural decision |
| Project | `:Project` | `name`, `description`, `createdAt` | Axolotl project |
| Schema | `:Schema`, `:Project` | `id`, `name`, `version`, `nodes`, `edges`, `createdAt` | Workflow schema |
| ExecutionRun | `:Execution`, `:Project` | `id`, `schemaId`, `startedAt`, `completedAt`, `status`, `durationMs`, `result` | Schema execution |
| AgentSession | `:Session`, `:Agent` | `id`, `agentType`, `startedAt`, `endedAt`, `context` | Agent session |
| Instruction | `:Instruction`, `:Agent` | `title`, `content`, `category`, `priority` | Agent instruction/rule |

### Relationship Types

| From | To | Type | Properties | Description |
|------|----|------|------------|-------------|
| Package | Package | `:CONTAINS` | Package hierarchy |
| Package | Class | `:CONTAINS` | Package → class |
| Class | Class | `:EXTENDS` | Inheritance |
| Class | Class | `:IMPLEMENTS` | Interface implementation |
| Class | Method | `:DECLARES` | Class declares method |
| Class | Field | `:DECLARES` | Class declares field |
| Class | Import | `:USES` | Class uses import |
| Method | Method | `:CALLS` | Method calls method |
| Method | Method | `:CONTAINS` | Method contains nested |
| Class | Class | `:DEPENDS_ON` | Class dependency |
| Decision | Class | `:AFFECTS` | Decision impacts class |
| Decision | Method | `:AFFECTS` | Decision impacts method |
| Decision | Package | `:AFFECTS` | Decision impacts package |
| Project | Schema | `:OWNS` | Project owns schema |
| Schema | ExecutionRun | `:PRODUCES` | Schema produces run |
| ExecutionRun | Decision | `:BASED_ON` | Execution based on decision |
| Project | AgentSession | `:HAS_SESSION` | Project has agent session |
| AgentSession | Instruction | `:APPLIED` | Session applied instruction |
| Decision | Decision | `:RELATED_TO` | Decisions related |

## Java Models (Spring Data Neo4j)

### Dependencies (add to pom.xml)

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-neo4j</artifactId>
</dependency>
```

### Entity Classes

```java
// backend/src/main/java/com/agent/orchestrator/graph/model/CodePackage.java
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
    
    // Constructors, getters, setters
    public CodePackage() {}
    
    public CodePackage(String name, String path) {
        this.name = name;
        this.path = path;
    }
}
```

```java
// backend/src/main/java/com/agent/orchestrator/graph/model/CodeClass.java
package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Node(labels = {"Class", "Code"})
public class CodeClass {
    
    @Id
    @GeneratedValue
    private Long id;
    
    @Property(name = "name")
    private String name;
    
    @Property(name = "qualifiedName")
    private String qualifiedName;
    
    @Property(name = "package")
    private String packageName;
    
    @Property(name = "filePath")
    private String filePath;
    
    @Property(name = "isAbstract")
    private boolean isAbstract = false;
    
    @Property(name = "isInterface")
    private boolean isInterface = false;
    
    @Property(name = "isEnum")
    private boolean isEnum = false;
    
    @Property(name = "visibility")
    private String visibility = "public";
    
    @Property(name = "createdAt")
    private Instant createdAt = Instant.now();
    
    @Relationship(type = "DECLARES", direction = Relationship.Direction.OUTGOING)
    private Set<CodeMethod> methods = new HashSet<>();
    
    @Relationship(type = "DECLARES", direction = Relationship.Direction.OUTGOING)
    private Set<CodeField> fields = new HashSet<>();
    
    @Relationship(type = "USES", direction = Relationship.Direction.OUTGOING)
    private Set<CodeImport> imports = new HashSet<>();
    
    @Relationship(type = "EXTENDS", direction = Relationship.Direction.OUTGOING)
    private CodeClass superClass;
    
    @Relationship(type = "IMPLEMENTS", direction = Relationship.Direction.OUTGOING)
    private Set<CodeClass> interfaces = new HashSet<>();
    
    @Relationship(type = "DEPENDS_ON", direction = Relationship.Direction.OUTGOING)
    private Set<CodeClass> dependencies = new HashSet<>();
    
    @Relationship(type = "CONTAINS", direction = Relationship.Direction.INCOMING)
    private CodePackage parentPackage;
    
    // Constructors, getters, setters
    public CodeClass() {}
    
    public CodeClass(String name, String qualifiedName, String packageName) {
        this.name = name;
        this.qualifiedName = qualifiedName;
        this.packageName = packageName;
    }
}
```

```java
// backend/src/main/java/com/agent/orchestrator/graph/model/CodeMethod.java
package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Node(labels = {"Method", "Code"})
public class CodeMethod {
    
    @Id
    @GeneratedValue
    private Long id;
    
    @Property(name = "name")
    private String name;
    
    @Property(name = "signature")
    private String signature;
    
    @Property(name = "returnType")
    private String returnType;
    
    @Property(name = "visibility")
    private String visibility = "public";
    
    @Property(name = "isStatic")
    private boolean isStatic = false;
    
    @Property(name = "isFinal")
    private boolean isFinal = false;
    
    @Property(name = "isAbstract")
    private boolean isAbstract = false;
    
    @Property(name = "lineNumber")
    private int lineNumber;
    
    @Property(name = "parameters")
    private String parameters;
    
    @Property(name = "createdAt")
    private Instant createdAt = Instant.now();
    
    @Relationship(type = "CALLS", direction = Relationship.Direction.OUTGOING)
    private Set<CodeMethod> calls = new HashSet<>();
    
    @Relationship(type = "CALLS", direction = Relationship.Direction.INCOMING)
    private Set<CodeMethod> calledBy = new HashSet<>();
    
    @Relationship(type = "DECLARES", direction = Relationship.Direction.INCOMING)
    private CodeClass parentClass;
    
    public CodeMethod() {}
    
    public CodeMethod(String name, String signature, String returnType) {
        this.name = name;
        this.signature = signature;
        this.returnType = returnType;
    }
}
```

```java
// backend/src/main/java/com/agent/orchestrator/graph/model/CodeField.java
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
    
    @Property(name = "initializer")
    private String initializer;
    
    @Property(name = "createdAt")
    private Instant createdAt = Instant.now();
    
    @Relationship(type = "DECLARES", direction = Relationship.Direction.INCOMING)
    private CodeClass parentClass;
    
    public CodeField() {}
    
    public CodeField(String name, String type) {
        this.name = name;
        this.type = type;
    }
}
```

```java
// backend/src/main/java/com/agent/orchestrator/graph/model/CodeImport.java
package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;
import java.time.Instant;

@Node(labels = {"Import", "Code"})
public class CodeImport {
    
    @Id
    @GeneratedValue
    private Long id;
    
    @Property(name = "qualifiedName")
    private String qualifiedName;
    
    @Property(name = "isStatic")
    private boolean isStatic = false;
    
    @Property(name = "isWildcard")
    private boolean isWildcard = false;
    
    @Property(name = "alias")
    private String alias;
    
    @Property(name = "createdAt")
    private Instant createdAt = Instant.now();
    
    @Relationship(type = "USES", direction = Relationship.Direction.INCOMING)
    private CodeClass importingClass;
    
    public CodeImport() {}
    
    public CodeImport(String qualifiedName) {
        this.qualifiedName = qualifiedName;
    }
}
```

```java
// backend/src/main/java/com/agent/orchestrator/graph/model/Decision.java
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
    private String status = "proposed"; // proposed, accepted, rejected, deprecated
    
    @Property(name = "decidedBy")
    private String decidedBy;
    
    @Property(name = "priority")
    private String priority = "MEDIUM"; // HIGH, MEDIUM, LOW
    
    @Property(name = "createdAt")
    private Instant createdAt = Instant.now();
    
    @Property(name = "decidedAt")
    private Instant decidedAt;
    
    @Property(name = "source")
    private String source; // opencode, cloudcode, manual
    
    @Property(name = "discussion")
    private String discussion; // Raw dialogue transcript
    
    // Relationships to code elements affected by this decision
    @Relationship(type = "AFFECTS", direction = Relationship.Direction.OUTGOING)
    private Set<CodeClass> impactedClasses = new HashSet<>();
    
    @Relationship(type = "AFFECTS", direction = Relationship.Direction.OUTGOING)
    private Set<CodeMethod> impactedMethods = new HashSet<>();
    
    @Relationship(type = "AFFECTS", direction = Relationship.Direction.OUTGOING)
    private Set<CodePackage> impactedPackages = new HashSet<>();
    
    @Relationship(type = "RELATED_TO", direction = Relationship.Direction.OUTGOING)
    private Set<Decision> relatedDecisions = new HashSet<>();
    
    public Decision() {}
    
    public Decision(String title, String description, String rationale) {
        this.title = title;
        this.description = description;
        this.rationale = rationale;
    }
}
```

```java
// backend/src/main/java/com/agent/orchestrator/graph/model/Project.java
package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Node(labels = {"Project"})
public class Project {
    
    @Id
    @GeneratedValue
    private Long id;
    
    @Property(name = "name")
    private String name;
    
    @Property(name = "description")
    private String description;
    
    @Property(name = "createdAt")
    private Instant createdAt = Instant.now();
    
    @Property(name = "updatedAt")
    private Instant updatedAt;
    
    @Property(name = "owner")
    private String owner;
    
    @Property(name = "status")
    private String status = "active";
    
    @Relationship(type = "OWNS", direction = Relationship.Direction.OUTGOING)
    private Set<WorkflowSchema> schemas = new HashSet<>();
    
    @Relationship(type = "HAS_SESSION", direction = Relationship.Direction.OUTGOING)
    private Set<AgentSession> sessions = new HashSet<>();
    
    public Project() {}
    
    public Project(String name, String description) {
        this.name = name;
        this.description = description;
    }
}
```

```java
// backend/src/main/java/com/agent/orchestrator/graph/model/WorkflowSchema.java
package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Node(labels = {"Schema", "Project"})
public class WorkflowSchema {
    
    @Id
    @GeneratedValue
    private Long id;
    
    @Property(name = "schemaId")
    private String schemaId;
    
    @Property(name = "name")
    private String name;
    
    @Property(name = "version")
    private String version = "1.0";
    
    @Property(name = "description")
    private String description;
    
    @Property(name = "json")
    private String json; // Full schema JSON
    
    @Property(name = "createdAt")
    private Instant createdAt = Instant.now();
    
    @Property(name = "updatedAt")
    private Instant updatedAt;
    
    @Property(name = "createdBy")
    private String createdBy;
    
    @Relationship(type = "PRODUCES", direction = Relationship.Direction.OUTGOING)
    private Set<ExecutionRun> executionRuns = new HashSet<>();
    
    @Relationship(type = "OWNS", direction = Relationship.Direction.INCOMING)
    private Project parentProject;
    
    public WorkflowSchema() {}
    
    public WorkflowSchema(String schemaId, String name) {
        this.schemaId = schemaId;
        this.name = name;
    }
}
```

```java
// backend/src/main/java/com/agent/orchestrator/graph/model/ExecutionRun.java
package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;
import java.time.Instant;

@Node(labels = {"Execution", "Project"})
public class ExecutionRun {
    
    @Id
    @GeneratedValue
    private Long id;
    
    @Property(name = "executionId")
    private String executionId;
    
    @Property(name = "schemaId")
    private String schemaId;
    
    @Property(name = "startedAt")
    private Instant startedAt;
    
    @Property(name = "completedAt")
    private Instant completedAt;
    
    @Property(name = "status")
    private String status; // running, completed, failed
    
    @Property(name = "durationMs")
    private Long durationMs;
    
    @Property(name = "result")
    private String result;
    
    @Property(name = "error")
    private String error;
    
    @Property(name = "input")
    private String input;
    
    @Property(name = "metrics")
    private String metrics; // JSON metrics
    
    @Relationship(type = "BASED_ON", direction = Relationship.Direction.OUTGOING)
    private Set<Decision> basedOnDecisions = new HashSet<>();
    
    @Relationship(type = "PRODUCES", direction = Relationship.Direction.INCOMING)
    private WorkflowSchema parentSchema;
    
    public ExecutionRun() {}
    
    public ExecutionRun(String executionId, String schemaId) {
        this.executionId = executionId;
        this.schemaId = schemaId;
    }
}
```

```java
// backend/src/main/java/com/agent/orchestrator/graph/model/Instruction.java
package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Node(labels = {"Instruction", "Agent"})
public class Instruction {
    
    @Id
    @GeneratedValue
    private Long id;
    
    @Property(name = "title")
    private String title;
    
    @Property(name = "content")
    private String content;
    
    @Property(name = "category")
    private String category; // coding, architecture, testing, documentation
    
    @Property(name = "priority")
    private String priority = "MEDIUM";
    
    @Property(name = "createdAt")
    private Instant createdAt = Instant.now();
    
    @Property(name = "updatedAt")
    private Instant updatedAt;
    
    @Property(name = "author")
    private String author;
    
    @Property(name = "source")
    private String source; // manual, extracted, generated
    
    @Relationship(type = "APPLIED", direction = Relationship.Direction.INCOMING)
    private Set<AgentSession> appliedInSessions = new HashSet<>();
    
    public Instruction() {}
    
    public Instruction(String title, String content, String category) {
        this.title = title;
        this.content = content;
        this.category = category;
    }
}
```

## Repositories

```java
// backend/src/main/java/com/agent/orchestrator/graph/repository/CodePackageRepository.java
package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.CodePackage;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CodePackageRepository extends Neo4jRepository<CodePackage, Long> {
    
    Optional<CodePackage> findByPath(String path);
    
    List<CodePackage> findByNameContaining(String nameFragment);
    
    @Query("MATCH (p:Package {path: $path})<-[:CONTAINS*]-(root:Package) WHERE root.parent IS NULL RETURN p")
    List<CodePackage> findRootPackages();
    
    @Query("MATCH (p:Package {path: $path})-[:CONTAINS]->(c:Class) RETURN count(c) AS classCount")
    int countClassesInPackage(@Param("path") String path);
}
```

```java
// backend/src/main/java/com/agent/orchestrator/graph/repository/CodeClassRepository.java
package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.CodeClass;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CodeClassRepository extends Neo4jRepository<CodeClass, Long> {
    
    Optional<CodeClass> findByQualifiedName(String qualifiedName);
    
    List<CodeClass> findByNameContaining(String nameFragment);
    
    List<CodeClass> findByPackageName(String packageName);
    
    @Query("""
        MATCH (c:Class)-[:DEPENDS_ON]->(dep:Class)
        WHERE c.qualifiedName = $qualifiedName
        RETURN dep
        ORDER BY dep.name
        """)
    List<CodeClass> findDependencies(@Param("qualifiedName") String qualifiedName);
    
    @Query("""
        MATCH (c:Class)-[:EXTENDS|IMPLEMENTS]->(parent:Class)
        WHERE c.qualifiedName = $qualifiedName
        RETURN parent
        """)
    List<CodeClass> findSuperTypes(@Param("qualifiedName") String qualifiedName);
    
    @Query("""
        MATCH (c:Class {packageName: $packageName})-[:DECLARES]->(m:Method)
        RETURN m ORDER BY m.lineNumber
        """)
    List<CodeClass> findMethodsInPackage(@Param("packageName") String packageName);
    
    @Query("""
        MATCH (c:Class)<-[:AFFECTS]-(d:Decision)
        WHERE d.status = 'accepted'
        RETURN c, d ORDER BY d.decidedAt DESC
        LIMIT $limit
        """)
    List<CodeClass> findRecentlyImpactedClasses(@Param("limit") int limit);
}
```

```java
// backend/src/main/java/com/agent/orchestrator/graph/repository/CodeMethodRepository.java
package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.CodeMethod;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CodeMethodRepository extends Neo4jRepository<CodeMethod, Long> {
    
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
        MATCH (m:Method)<-[:AFFECTS]-(d:Decision)
        WHERE d.status = 'accepted'
        RETURN m ORDER BY d.decidedAt DESC
        LIMIT $limit
        """)
    List<CodeMethod> findRecentlyImpactedMethods(@Param("limit") int limit);
}
```

```java
// backend/src/main/java/com/agent/orchestrator/graph/repository/DecisionRepository.java
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
public interface DecisionRepository extends Neo4jRepository<Decision, Long> {
    
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
        MATCH (d:Decision)-[:RELATED_TO]->(related:Decision)
        WHERE d.id = $decisionId
        RETURN related
        """)
    List<Decision> findRelatedDecisions(@Param("decisionId") Long decisionId);
    
    @Query("""
        MATCH (d:Decision)
        WHERE d.title CONTAINS $keyword OR d.description CONTAINS $keyword
           OR d.rationale CONTAINS $keyword
        RETURN d ORDER BY d.createdAt DESC
        LIMIT $limit
        """)
    List<Decision> search(@Param("keyword") String keyword, @Param("limit") int limit);
}
```

```java
// backend/src/main/java/com/agent/orchestrator/graph/repository/ProjectRepository.java
package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.graph.model.Project;
import com.agent.orchestrator.graph.model.WorkflowSchema;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectRepository extends Neo4jRepository<Project, Long> {
    
    Optional<Project> findByName(String name);
    
    List<Project> findByOwner(String owner);
    
    @Query("""
        MATCH (p:Project)-[:OWNS]->(s:Schema)
        WHERE p.name = $projectName
        RETURN s ORDER BY s.updatedAt DESC
        """)
    List<WorkflowSchema> findSchemasByProject(@Param("projectName") String projectName);
    
    @Query("""
        MATCH (p:Project)-[:HAS_SESSION]->(s:AgentSession)
        WHERE p.name = $projectName
        RETURN s ORDER BY s.startedAt DESC
        LIMIT $limit
        """)
    List<Project> findRecentSessions(@Param("projectName") String projectName, @Param("limit") int limit);
}
```

## Service Layer

```java
// backend/src/main/java/com/agent/orchestrator/graph/GraphMemoryService.java
package com.agent.orchestrator.graph;

import com.agent.orchestrator.graph.model.*;
import com.agent.orchestrator.graph.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class GraphMemoryService {
    
    private static final Logger log = LoggerFactory.getLogger(GraphMemoryService.class);
    
    private final CodePackageRepository packageRepo;
    private final CodeClassRepository classRepo;
    private final CodeMethodRepository methodRepo;
    private final DecisionRepository decisionRepo;
    private final ProjectRepository projectRepo;
    
    @Value("${axolotl.graph.enabled:true}")
    private boolean enabled;
    
    public GraphMemoryService(
            CodePackageRepository packageRepo,
            CodeClassRepository classRepo,
            CodeMethodRepository methodRepo,
            DecisionRepository decisionRepo,
            ProjectRepository projectRepo) {
        this.packageRepo = packageRepo;
        this.classRepo = classRepo;
        this.methodRepo = methodRepo;
        this.decisionRepo = decisionRepo;
        this.projectRepo = projectRepo;
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    // ==================== Code Operations ====================
    
    @Transactional
    public CodePackage savePackage(String name, String path) {
        CodePackage pkg = new CodePackage(name, path);
        return packageRepo.save(pkg);
    }
    
    @Transactional
    public CodeClass saveClass(String name, String qualifiedName, String packageName, 
                            String filePath, boolean isInterface) {
        CodeClass clazz = new CodeClass(name, qualifiedName, packageName);
        clazz.setFilePath(filePath);
        clazz.setInterface(isInterface);
        return classRepo.save(clazz);
    }
    
    @Transactional
    public CodeMethod saveMethod(String classQualifiedName, String name, String signature, String returnType,
                           int lineNumber, String visibility, boolean isStatic) {
        CodeClass clazz = classRepo.findByQualifiedName(classQualifiedName)
                .orElseThrow(() -> new IllegalArgumentException("Class not found: " + classQualifiedName));
        
        CodeMethod method = new CodeMethod(name, signature, returnType);
        method.setLineNumber(lineNumber);
        method.setVisibility(visibility);
        method.setStatic(isStatic);
        method.setParentClass(clazz);
        
        return methodRepo.save(method);
    }
    
    @Transactional
    public void linkMethodCall(String callerSignature, String calleeSignature) {
        CodeMethod caller = methodRepo.findBySignature(callerSignature)
                .orElseThrow(() -> new IllegalArgumentException("Caller not found: " + callerSignature));
        CodeMethod callee = methodRepo.findBySignature(calleeSignature)
                .orElseThrow(() -> new IllegalArgumentException("Callee not found: " + calleeSignature));
        
        caller.getCalls().add(callee);
        methodRepo.save(caller);
    }
    
    @Transactional
    public void linkClassDependency(String fromQualifiedName, String toQualifiedName) {
        CodeClass from = classRepo.findByQualifiedName(fromQualifiedName)
                .orElseThrow(() -> new IllegalArgumentException("Class not found: " + fromQualifiedName));
        CodeClass to = classRepo.findByQualifiedName(toQualifiedName)
                .orElseThrow(() -> new IllegalArgumentException("Dependency not found: " + toQualifiedName));
        
        from.getDependencies().add(to);
        classRepo.save(from);
    }
    
    // ==================== Decision Operations ====================
    
    @Transactional
    public Decision addDecision(String title, String description, String rationale,
                             String decidedBy, String priority) {
        Decision decision = new Decision(title, description, rationale);
        decision.setDecidedBy(decidedBy);
        decision.setPriority(priority);
        decision.setStatus("accepted");
        decision.setDecidedAt(Instant.now());
        
        return decisionRepo.save(decision);
    }
    
    @Transactional
    public void linkDecisionToClass(Long decisionId, String classQualifiedName) {
        Decision decision = decisionRepo.findById(decisionId)
                .orElseThrow(() -> new IllegalArgumentException("Decision not found: " + decisionId));
        CodeClass clazz = classRepo.findByQualifiedName(classQualifiedName)
                .orElseThrow(() -> new IllegalArgumentException("Class not found: " + classQualifiedName));
        
        decision.getImpactedClasses().add(clazz);
        decisionRepo.save(decision);
    }
    
    @Transactional
    public void linkDecisionToMethod(Long decisionId, String methodSignature) {
        Decision decision = decisionRepo.findById(decisionId)
                .orElseThrow(() -> new IllegalArgumentException("Decision not found: " + decisionId));
        CodeMethod method = methodRepo.findBySignature(methodSignature)
                .orElseThrow(() -> new IllegalArgumentException("Method not found: " + methodSignature));
        
        decision.getImpactedMethods().add(method);
        decisionRepo.save(decision);
    }
    
    @Transactional
    public void linkDecisionToDecision(Long fromId, Long toId) {
        Decision from = decisionRepo.findById(fromId)
                .orElseThrow(() -> new IllegalArgumentException("From decision not found"));
        Decision to = decisionRepo.findById(toId)
                .orElseThrow(() -> new IllegalArgumentException("To decision not found"));
        
        from.getRelatedDecisions().add(to);
        decisionRepo.save(from);
    }
    
    // ==================== Search Operations ====================
    
    public List<CodeClass> findClassesInPackage(String packageName) {
        return classRepo.findByPackageName(packageName);
    }
    
    public List<CodeClass> findClassDependencies(String qualifiedName) {
        return classRepo.findDependencies(qualifiedName);
    }
    
    public List<CodeMethod> findMethodCallers(String signature) {
        return methodRepo.findCallers(signature);
    }
    
    public List<CodeMethod> findMethodCallees(String signature) {
        return methodRepo.findCallees(signature);
    }
    
    public List<Decision> searchDecisions(String keyword, int limit) {
        return decisionRepo.search(keyword, limit);
    }
    
    public List<Decision> findDecisionsForClass(String classQualifiedName) {
        return decisionRepo.findByAffectedClass(classQualifiedName);
    }
    
    public List<Decision> findRecentDecisions(int limit) {
        return decisionRepo.findByStatusSince("accepted", Instant.now().minusSeconds(86400 * 30));
    }
    
    // ==================== Context Building ====================
    
    /**
     * Build context for AI agent: find code elements and decisions related to query.
     */
    public String buildContext(String query, int depth) {
        if (!enabled) return "";
        
        StringBuilder sb = new StringBuilder();
        sb.append("[GRAPH MEMORY - ").append(query).append("]\n\n");
        
        // Search for relevant decisions
        List<Decision> decisions = decisionRepo.search(query, 5);
        if (!decisions.isEmpty()) {
            sb.append("## Релевантные решения\n");
            for (Decision d : decisions) {
                sb.append(String.format("### %s [%s]\n", d.getTitle(), d.getStatus().toUpperCase()));
                sb.append(d.getDescription()).append("\n");
                if (d.getRationale() != null) {
                    sb.append("**Обоснование:** ").append(d.getRationale()).append("\n");
                }
                if (!d.getImpactedClasses().isEmpty()) {
                    sb.append("**Затронутые классы:** ");
                    sb.append(d.getImpactedClasses().stream()
                            .map(CodeClass::getQualifiedName)
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("")).append("\n");
                }
                sb.append("\n");
            }
        }
        
        // Search for code elements
        List<CodeClass> classes = classRepo.findByNameContaining(query);
        if (!classes.isEmpty()) {
            sb.append("## Релевантные классы\n");
            for (CodeClass c : classes) {
                sb.append(String.format("- `%s` (%s)\n", c.getQualifiedName(), c.getPackageName()));
            }
        }
        
        sb.append("\n[END GRAPH MEMORY]");
        return sb.toString();
    }
}
```

## Configuration

```yaml
# backend/src/main/resources/application.yml

spring:
  neo4j:
    uri: ${NEO4J_URI:bolt://localhost:7687}
    authentication:
      username: ${NEO4J_USERNAME:neo4j}
      password: ${NEO4J_PASSWORD:neo4j}
    pool:
      max-connection-pool-size: 50
      connection-acquisition-timeout: 60s
      max-lifetime: 3600s
      max-idle-time: 1800s

# Alternative: use embedded Neo4j for testing
# spring.neo4j.uri: file:./neo4j-data/test.db
```

```properties
# backend/src/main/resources/application.properties (for H2 fallback in tests)
# spring.test.neo4j.enabled=true
# neo4j.test.mode=embedded
```

## Cypher Query Examples

### "Find all classes related to module X and decisions that affected them after date Y"

```cypher
MATCH (d:Decision)-[r:AFFECTS]->(c:Class)
WHERE c.packageName CONTAINS 'com.agent.orchestrator'
  AND d.decidedAt > datetime('2024-01-01')
RETURN c.qualifiedName, d.title, d.decidedAt, d.status
ORDER BY d.decidedAt DESC
LIMIT 50
```

### "Find all methods calling a specific method"

```cypher
MATCH (caller:Method)-[:CALLS]->(callee:Method {signature: 'execute(String, String)'})
RETURN caller.signature, caller.parentClass.qualifiedName
ORDER BY caller.lineNumber
```

### "Find decision impact chain"

```cypher
MATCH path = (d1:Decision)-[:RELATED_TO*1..3]->(d2:Decision)
WHERE d1.title CONTAINS ' архитектура'
RETURN path
LIMIT 20
```

### "Full-text search across decisions and code"

```cypher
CALL db.index.fulltext.createNodeIndex('content', ['Decision', 'CodeClass', 'CodeMethod'], ['title', 'description', 'name'])
```

## Migration Plan (Step by Step)

### Phase 1: Prototype (Week 1-2)
1. Add Neo4j dependencies to pom.xml
2. Create entity models (CodePackage, CodeClass, CodeMethod, Decision)
3. Configure Neo4j connection
4. Create basic repositories
5. Create GraphMemoryService with CRUD operations
6. Test with embedded Neo4j or local instance

### Phase 2: Core Implementation (Week 3-4)
1. Create remaining entities (Project, Schema, ExecutionRun, Instruction)
2. Implement code parser (JavaParser integration)
3. Implement decision import from LLM
4. Move Schema/Execution to Neo4j
5. Create search operations
6. Create context building for agents

### Phase 3: Integration (Week 5-6)
1. Integrate with SchemaService (replace MemPalaceClient calls)
2. Create MCP server for Neo4j
3. Add full-text indexes
4. Performance tuning
5. Test migration scripts

### Phase 4: Production (Week 7-8)
1. Set up Neo4j cluster (if needed)
2. Implement backup/restore
3. Documentation
4. Training and handoff

## Initial Seeding: Code Parsing

```java
// backend/src/main/java/com/agent/orchestrator/graph/loader/CodebaseLoader.java
package com.agent.orchestrator.graph.loader;

import com.agent.orchestrator.graph.model.*;
import com.agent.orchestrator.graph.repository.*;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Component
public class CodebaseLoader {
    
    private static final Logger log = LoggerFactory.getLogger(CodebaseLoader.class);
    
    private final CodePackageRepository packageRepo;
    private final CodeClassRepository classRepo;
    private final CodeMethodRepository methodRepo;
    private final JavaParser javaParser;
    
    public CodebaseLoader(
            CodePackageRepository packageRepo,
            CodeClassRepository classRepo,
            CodeMethodRepository methodRepo) {
        this.packageRepo = packageRepo;
        this.classRepo = classRepo;
        this.methodRepo = methodRepo;
        
        ParserConfiguration config = new ParserConfiguration()
                .setStoreTokens(true)
                .setDetectLanguage(true);
        this.javaParser = new JavaParser(config);
    }
    
    /**
     * Parse and load all Java files from a directory.
     */
    @Transactional
    public int loadDirectory(Path directory, String basePackage) throws IOException {
        int loaded = 0;
        
        try (var stream = Files.walk(directory)) {
            List<Path> javaFiles = stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
            
            for (Path file : javaFiles) {
                try {
                    loadFile(file, basePackage);
                    loaded++;
                } catch (Exception e) {
                    log.error("Error loading {}: {}", file, e.getMessage());
                }
            }
        }
        
        log.info("Loaded {} classes from {}", loaded, directory);
        return loaded;
    }
    
    private void loadFile(Path file, String basePackage) throws IOException {
        String content = Files.readString(file);
        var parseResult = javaParser.parse(content);
        
        if (!parseResult.isSuccessful()) {
            parseResult.getProblems().forEach(p -> log.warn("Parse problem: {}", p.getMessage()));
            return;
        }
        
        var cu = parseResult.getResult().orElseThrow();
        
        String packageName = cu.getPackageDeclaration()
                .map(p -> p.getNameAsString())
                .orElse(basePackage);
        
        // Ensure package exists
        CodePackage pkg = packageRepo.findByPath(packageName)
                .orElseGet(() -> {
                    String[] parts = packageName.split("\\.");
                    String path = packageName;
                    String name = parts[parts.length - 1];
                    return packageRepo.save(new CodePackage(name, path));
                });
        
        // Parse classes
        for (TypeDeclaration<?> type : cu.getTypes()) {
            if (type instanceof ClassOrInterfaceDeclaration clazz) {
                loadClass(clazz, packageName, file.toString(), pkg);
            }
        }
    }
    
    private CodeClass loadClass(ClassOrInterfaceDeclaration clazz, 
                             String packageName, String filePath, CodePackage parent) {
        String qualifiedName = packageName + "." + clazz.getNameAsString();
        
        CodeClass entity = classRepo.findByQualifiedName(qualifiedName)
                .orElseGet(() -> {
                    CodeClass c = new CodeClass(
                            clazz.getNameAsString(),
                            qualifiedName,
                            packageName
                    );
                    c.setFilePath(filePath);
                    c.setAbstract(clazz.isAbstract());
                    c.setInterface(clazz.isInterface());
                    
                    String vis = clazz.getModifiers().stream()
                            .map(m -> m.getKeyword().asString())
                            .findFirst()
                            .orElse("package");
                    c.setVisibility(vis);
                    
                    c.setParentPackage(parent);
                    return classRepo.save(c);
                });
        
        // Parse methods
        for (MethodDeclaration method : clazz.getMethods()) {
            loadMethod(method, entity);
        }
        
        // Parse fields
        for (FieldDeclaration field : clazz.getFields()) {
            loadField(field, entity);
        }
        
        return entity;
    }
    
    private void loadMethod(MethodDeclaration method, CodeClass parent) {
        String signature = parent.getQualifiedName() + "." 
                + method.getNameAsString() 
                + method.getParameters();
        
        CodeMethod entity = methodRepo.findBySignature(signature)
                .orElseGet(() -> {
                    String returnType = method.getTypeAsString();
                    String vis = method.getModifiers().stream()
                            .map(m -> m.getKeyword().asString())
                            .findFirst()
                            .orElse("public");
                    
                    CodeMethod m = new CodeMethod(
                            method.getNameAsString(),
                            signature,
                            returnType
                    );
                    m.setVisibility(vis);
                    m.setStatic(method.getModifiers().stream()
                            .anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC));
                    m.setLineNumber(method.getBegin().map(l -> l.line).orElse(0));
                    m.setParameters(method.getParametersAsString());
                    m.setParentClass(parent);
                    
                    return methodRepo.save(m);
                });
        
        // Parse method calls (simplified - would need deeper AST analysis)
        // This is a placeholder for more sophisticated call graph extraction
    }
    
    private void loadField(FieldDeclaration field, CodeClass parent) {
        // Similar implementation for fields
    }
}
```

### Add JavaParser dependency:

```xml
<dependency>
    <groupId>com.github.javaparser</groupId>
    <artifactId>javaparser-core</artifactId>
    <version>3.25.10</version>
</dependency>
```

## Performance Recommendations

### Indexes

```cypher
// Package indexes
CREATE INDEX package_path IF NOT EXISTS FOR (p:Package) ON (p.path);
CREATE INDEX package_name IF NOT EXISTS FOR (p:Package) ON (p.name);

// Class indexes
CREATE INDEX class_qualified IF NOT EXISTS FOR (c:Class) ON (c.qualifiedName);
CREATE INDEX class_package IF NOT EXISTS FOR (c:Class) ON (c.packageName);

// Method indexes
CREATE INDEX method_sig IF NOT EXISTS FOR (m:Method) ON (m.signature);
CREATE INDEX method_class IF NOT EXISTS FOR (m:Method) ON (m.parentClass);

// Decision indexes
CREATE INDEX decision_title IF NOT EXISTS FOR (d:Decision) ON (d.title);
CREATE INDEX decision_status IF NOT EXISTS FOR (d:Decision) ON (d.status);
CREATE INDEX decision_date IF NOT EXISTS FOR (d:Decision) ON (d.decidedAt);

// Full-text index
CALL db.index.fulltext.createNodeIndex('search', ['Decision', 'CodeClass', 'CodeMethod'], 
    ['title', 'description', 'name', 'rationale']);
```

### Estimated Data Volume

| Element | Count (current) | Count (6 months) | Count (1 year) |
|--------|----------------|----------------|----------------|
| Packages | ~50 | ~100 | ~200 |
| Classes | ~200 | ~500 | ~1,000 |
| Methods | ~1,500 | ~4,000 | ~8,000 |
| Fields | ~800 | ~2,000 | ~4,000 |
| Decisions | ~50 | ~200 | ~500 |
| Executions | ~1,000 | ~10,000 | ~50,000 |
| Instructions | ~100 | ~500 | ~1,000 |

**Total expected**: ~100k nodes, ~500k relationships (6 months)
**Neo4j Community**: handles up to ~1M nodes efficiently

### Profiling Queries

```cypher
// Slowest queries
CALL dbms.listQueries() YIELD query, elapsedTime, cpuTime, status
WHERE status = 'running'
RETURN query, cpuTime
ORDER BY cpuTime DESC
LIMIT 10;

// Memory usage
CALL dbms.memory() YIELD name, total, used, free
RETURN name, used / 1024 / 1024 AS usedMB;

// Index stats
CALL db.index.fulltext.nodeIndexStats() YIELD indexName, entriesIndexed;
```

### Scaling Strategy

| Mode | Nodes | Strategy |
|------|------|----------|
| Development | <10k | Single Neo4j, default config |
| Production | <100k | Single Neo4j, tuned pool |
| Enterprise | 100k+ | Neo4j cluster (Causal Cluster) |

## Summary

This specification provides:
1. **Complete graph schema** with node types and relationships
2. **Java entity models** with Spring Data Neo4j annotations
3. **Repository interfaces** with custom Cypher queries
4. **GraphMemoryService** for CRUD and search operations
5. **Configuration** for development and production
6. **Cypher query examples**
7. **Step-by-step migration plan** (8 weeks)
8. **Code parsing** for initial seeding
9. **Performance recommendations**

The implementation uses Neo4j Community Edition (free, open-source) with local installation or embedded mode for development.