---
date: 2026-05-13
topic: Neo4j id() → elementId() Migration
status: draft
---

## Problem Statement

Spring Data Neo4j 7.2.0 generates relationship-loading queries using the deprecated `id()` function, producing a warning on every entity load:

```
Neo.ClientNotification.Statement.FeatureDeprecationWarning: 
This feature is deprecated and will be removed in future versions.
  MATCH (c) OPTIONAL MATCH (c)-[r:IMPLEMENTS]->(t)
  WITH collect(id(c)), collect(id(t)), collect(id(r))
```

Quitting empty warnings is Option 1. We're doing Option 3: truly migrating off Neo4j's internal `id()` system.

## Constraints

- No breaking changes to existing API contracts
- All custom `@Query` methods use domain properties (hash, qualifiedName, signature) — not `id()` — so they're safe
- 5 entities with `Long @Id @GeneratedValue` and `Neo4jRepository<T, Long>` need migration
- 4 entities (GraphWorkflowSchema, ProviderConfig, LlmEndpoint, Plan) already use String `@Id` — no change needed
- Existing Neo4j data for code graph entities is ephemeral (re-imported from source via `update-graph.sh`)
- `scripts/migrate-to-neo4j.py` does not reference `id()` — safe

## Approach

Replace `Long @Id @GeneratedValue` with `String @Id` (UUID-based) across all 5 code graph entities.

### Why not just upgrade Spring Boot?
Upgrading SB 3.2.0 → 3.3.x would make SDN generate `elementId()` in queries but keep us coupled to Neo4j's internal ID system. String UUIDs decouple entirely — entities survive Neo4j exports/imports unchanged.

## Architecture

### Entities to migrate (5 files)

| Entity | Current ID | New ID | Constructors need UUID? |
|--------|-----------|--------|------------------------|
| `CodeClass.java` | `@Id @GeneratedValue Long` | `@Id String` | Yes (3 constructors: default, 3-arg, possibly builder) |
| `CodeMethod.java` | `@Id @GeneratedValue Long` | `@Id String` | Yes (2 constructors) |
| `CodeField.java` | `@Id @GeneratedValue Long` | `@Id String` | Yes (2 constructors) |
| `CodePackage.java` | `@Id @GeneratedValue Long` | `@Id String` | Yes (2 constructors) |
| `Decision.java` | `@Id @GeneratedValue Long` | `@Id String` | Yes (2 constructors) |

### Repositories to migrate (5 files)

| Repository | Current | New |
|-----------|---------|-----|
| `CodeClassRepository` | `Neo4jRepository<CodeClass, Long>` | `Neo4jRepository<CodeClass, String>` |
| `CodeMethodRepository` | `Neo4jRepository<CodeMethod, Long>` | `Neo4jRepository<CodeMethod, String>` |
| `CodeFieldRepository` | `Neo4jRepository<CodeField, Long>` | `Neo4jRepository<CodeField, String>` |
| `CodePackageRepository` | `Neo4jRepository<CodePackage, Long>` | `Neo4jRepository<CodePackage, String>` |
| `DecisionRepository` | `Neo4jRepository<Decision, Long>` | `Neo4jRepository<Decision, String>` |

### Services to check (no changes expected)

| Service | Uses `.getId()`? | Notes |
|---------|-----------------|-------|
| `ParallelCodebaseImporter` | No | Uses `findByHash`, `save()` — sets domain props only |
| `CodebaseLoader` | No | Same pattern — creates entities, saves |
| `GraphMemoryService` | No | Creates `Decision` entities directly |
| `UnifiedMemoryService` | No | Same pattern |
| `BatchPlanner` | No | Uses domain properties |
| `ContextCurationService` | No | Uses domain properties |
| `AstPatternSearchService` | No | Uses domain properties |

### No changes needed

- All `@Query` annotations (19 queries across 5 repos) use domain props only — no `id()` calls
- `Neo4jIndexesInitializer` — indexes on `hash`, `qualifiedName`, `signature`, `name` — not ID-dependent
- `Neo4jSchemaRepository`, `Neo4jPlanRepository` — driver-based, use String IDs already
- `scripts/migrate-to-neo4j.py` — no `id()` references

## Data Flow

```
Entity constructor
  → this.id = UUID.randomUUID().toString()
  → Repository.save(entity)
  → SDN uses String @Id as element identity (no @GeneratedValue needed)
  → Auto-generated relationship queries use elementId() instead of id()
  → Warning gone
```

### UUID generation details

- `java.util.UUID.randomUUID().toString()` — standard, no collisions
- Set in the primary constructor of each entity
- No `@GeneratedValue` annotation — ID is application-managed
- Entity equality: SDN compares by `@Id` field, so `equals()`/`hashCode()` should work as before

## Migration Steps

### For fresh code graphs (no existing data)

The code graph is rebuilt from source on each `update-graph.sh` run. After this change:
1. Run `scripts/update-graph.sh` — the importer creates entities with UUID IDs
2. Nodes get new String IDs instead of Neo4j internal Long IDs
3. All `findBy*` methods work identically since they match on domain properties

### For existing Neo4j data (if preserving Decisions)

If `Decision` nodes exist and need preservation (unlikely — they're part of the code graph analysis), a one-shot migration script would:
```cypher
MATCH (n:Decision) WHERE n.id IS NULL
SET n.id = randomUUID()
```
But since the entire code graph is re-imported on each run, this is unnecessary.

## Error Handling

| Scenario | Behavior |
|----------|----------|
| Entity saved without UUID set | SDN will throw `IllegalStateException` — "id is not set" |
| Duplicate UUID (collision) | Statistically impossible for code graph volume (<10^5 nodes) |
| Existing node has no `id` string property | `MERGE` will create a new node (intended — code graph is fresh) |

## Testing Strategy

1. **Compile**: `mvn compile` — catches wrong generic types in repositories
2. **Unit**: Existing tests for repositories — verify `findByHash`, `findByQualifiedName` still work
3. **Integration**: Run `update-graph.sh` → verify code graph loads without id() warnings
4. **Final check**: Tail logs, grep for `FeatureDeprecationWarning` — should be 0

## Phase 2: Spring Boot Upgrade (required for fix)

The `@Id` migration alone is **insufficient**. SDN 7.2.0 (shipped with Spring Boot 3.2.0) hardcodes `id()` in its internal entity-loading and relationship-resolution queries regardless of `@Id` type. The fix requires SDN 7.3+ (Spring Boot 3.3+).

### Upgrade path

| Dependency | Current | Target | Reason |
|-----------|---------|--------|--------|
| Spring Boot | `3.2.0` | `3.3.13` | Ships SDN 7.3+ with `elementId()` |
| Spring AI | `1.1.4` | keep (verify compat) | Spring AI 1.1.x now targets Boot 3.5+ but should work with 3.3 |

### Changes required

**1. `pom.xml`** — change parent version:
```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.3.13</version>
</parent>
```

**2. Check Spring AI compatibility** — Spring AI 1.1.4 uses its own BOM with independent transitive dependency versions. If compilation fails, bump `spring-ai.version` to `1.2.x` (compatible with Boot 3.3).

**3. Address API changes in Spring Boot 3.3** from 3.2:
- Spring Security 6.3 (was 6.2) — check if `SecurityConfig.java` uses any deprecated APIs
- Spring Framework 6.2 (was 6.1) — minor API cleanup, unlikely to affect us
- Micrometer 1.14 (was 1.12) — `micrometer-registry-prometheus` explicit version needs bump if incompatible

**4. Explicit dependency versions to review:**
- `micrometer-registry-prometheus` (currently `1.12.0`) — may conflict with Boot 3.3's managed `1.14.x`
- Remove explicit version or bump to match Boot 3.3

### Risk assessment

| Risk | Impact | Mitigation |
|------|--------|-----------|
| Spring Security API change | Break auth | Projects using `http.authorizeHttpRequests()` are fine (we already use it) |
| Spring AI incompatibility | LLM provider break | Test compile first; bump `spring-ai` to 1.2.x if needed |
| Micrometer version conflict | Build warning | Remove explicit version (let Boot manage it) |
| Test breakage | CI fails | Run full test suite; Spring Boot 3.3 test improvements are backward-compatible |

### Verification

1. `mvn compile -q` — must pass
2. `mvn test -q` — all tests must pass
3. Start app, load code graph → grep for `FeatureDeprecationWarning` → zero matches

## Open Questions

- Spring AI 1.1.4 compatibility with Boot 3.3.x — will be determined by test compile
