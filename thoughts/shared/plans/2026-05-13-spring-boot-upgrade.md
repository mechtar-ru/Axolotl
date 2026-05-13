# Spring Boot 3.2.0 → 3.3.13 Upgrade — Implementation Plan

**Goal:** Upgrade Spring Boot parent from 3.2.0 to 3.3.13 to ship SDN 7.3+ (fixes `id()` → `elementId()` in Neo4j relationship queries). This is Phase 2 of the Neo4j elementId migration.

**Architecture:** Single-file change to `pom.xml` only. This is a parent-version bump with dependency cleanup. No Java code changes expected — SecurityConfig uses modern `SecurityFilterChain` DSL, no deprecated API usage found. Spring AI 1.1.4 targets Boot 3.5.x, may need fallback if compilation fails.

**Design:** `thoughts/shared/designs/2026-05-13-neo4j-elementId-migration.md` (Phase 2 section)

### Key decisions (design gaps filled)

| Gap | Decision |
|-----|----------|
| Micrometer explicit version | Remove explicit `<version>1.12.0</version>` — let Boot 3.3.13 manage Micrometer 1.14.x |
| Spring AI compatibility | Spring AI 1.1.x targets Boot **3.5.x** (not 3.3.x). Bump to 1.1.5 (latest 1.1.x) but pin Boot-managed deps via parent. If compile fails, exclude conflicting transitive deps from Spring AI starters. |
| SecurityConfig compatibility | Uses `SecurityFilterChain` DSL with `authorizeHttpRequests` — no changes needed for Spring Security 6.3 |
| Deprecated API usage | No `WebMvcConfigurerAdapter`, `HandlerInterceptorAdapter` found — zero Java code changes needed |
| Testcontainers + Postgres | Explicit versions (1.19.3) kept — Boot 3.3 doesn't manage these |
| Springdoc OpenAPI | Explicit version (2.3.0) kept — Boot 3.3 doesn't manage this |
| Resilience4j | Explicit versions (2.2.0) kept — Boot 3.3 manages 2.2.x, compatible |
| Bucket4j | Explicit version (8.7.0) kept — no conflict with Boot-managed deps |

---

## Dependency Graph

```
Batch 1 (parallel — 2 tasks): 1.1, 1.2 [pom.xml edits - independent edits, same file]
Batch 2 (sequential):         2.1               [compile verify]
Batch 3 (conditional):        3.1               [Spring AI fix if compile fails]
Batch 4 (sequential):         4.1               [full test suite]
Batch 5 (sequential):         5.1               [runtime verification]
```

---

## Batch 1: pom.xml Edits

Tasks in this batch both modify `pom.xml`. Since they're different sections (parent vs dependencies), they can be applied sequentially within the single task, or in parallel using `edit` at different locations — but safest to run as one sequential task to avoid conflicts.

### Task 1.1: Update parent version + remove explicit Micrometer version

**File:** `backend/pom.xml`
**Test:** none (config change, verify via compile)
**Depends:** none

**Edit 1 — Parent version (line 16):**

Old:
```xml
        <version>3.2.0</version>
```
New:
```xml
        <version>3.3.13</version>
```

**Edit 2 — Remove explicit Micrometer version (lines 76-80):**

Old:
```xml
        <!-- Micrometer Prometheus for metrics -->
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
            <version>1.12.0</version>
        </dependency>
```
New:
```xml
        <!-- Micrometer Prometheus for metrics (version managed by Spring Boot parent) -->
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>
```

**Verification of changes (after both edits):**
```bash
cd backend && grep -n "version" pom.xml | head -5
# Should show: <version>3.3.13</version> for parent
grep -n "micrometer-registry-prometheus" pom.xml
# Should show: no <version> on the line after the dependency
```

**Commit:** `🔧 build: upgrade Spring Boot 3.2.0 → 3.3.13, drop explicit micrometer version`

---

## Batch 2: Compile Verification

### Task 2.1: Compile project

**File:** none (verification only)
**Test:** none
**Depends:** 1.1

**Action:** Run Maven compile to detect:
1. Spring AI 1.1.4 compatibility (it targets Boot 3.5.x — may fail)
2. Any other dependency version conflicts
3. Any compilation errors from API changes

```bash
cd backend && mvn compile -q 2>&1
```

**Expected outcomes:**

| Outcome | Action |
|---------|--------|
| `BUILD SUCCESS` | ✅ Proceed to Batch 4 (tests) |
| Spring AI error (class not found, method not found) | ⚠️ Proceed to Batch 3 (fix Spring AI) |
| Other dependency error | ⚠️ Investigate and fix individually |

**Note:** Look specifically for errors like:
- `java.lang.NoSuchMethodError` — Spring AI calling a Boot 3.5 API that doesn't exist in 3.3
- `Cannot resolve symbol` — incompatible dependency version
- `Package not found` — removed API

---

## Batch 3 (Conditional): Fix Spring AI Incompatibility

Only if Batch 2 fails with Spring AI errors. Spring AI 1.1.x explicitly targets Boot **3.5.x**, so incompatibility is likely.

### Task 3.1: Fix Spring AI compilation

**File:** `backend/pom.xml`
**Test:** none
**Depends:** 2.1 (only if compile failed)

**Strategy:** Spring AI starters bring conflicting transitive dependencies. Two possible fixes:

**Option A — Keep Spring AI 1.1.5, exclude conflicting transitive deps (PREFERRED):**

Add exclusion to both Spring AI starter dependencies:

```xml
        <!-- Spring AI -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-ollama</artifactId>
            <version>${spring-ai.version}</version>
            <exclusions>
                <!-- Spring AI 1.1.x targets Boot 3.5.x — exclude its transitive
                     deps that conflict with Boot 3.3.x managed versions -->
                <exclusion>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>*</artifactId>
                </exclusion>
                <exclusion>
                    <groupId>org.springframework</groupId>
                    <artifactId>*</artifactId>
                </exclusion>
            </exclusions>
        </dependency>
```

**Option B — Remove Spring AI starters entirely (if Option A fails):**

Since the project already has hand-rolled LLM providers (OllamaProvider, OpenAiProvider, etc.) using `java.net.http.HttpClient`, the Spring AI starters are redundant. Remove:

```xml
        <!-- Delete these two blocks entirely -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-ollama</artifactId>
            <version>${spring-ai.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-openai</artifactId>
            <version>${spring-ai.version}</version>
        </dependency>
```

Also remove the `spring-ai.version` property and `spring-ai-bom` from `<dependencyManagement>`.

**Verify:**
```bash
cd backend && mvn compile -q
```

**Decision criteria:**
- Try Option A first (exclusions)
- If still failing, use Option B (remove entirely)
- If Option B is used, verify `SpringAiConfig.java` and `SpringAiLlmProvider.java` can still compile or need adjustment

**Commit (if needed):** `🔧 build: fix Spring AI compatibility with Boot 3.3 — exclude conflicting transitive deps`

---

## Batch 4: Test Verification

### Task 4.1: Run full test suite

**File:** none (verification only)
**Test:** none
**Depends:** 2.1 (or 3.1 if Spring AI fix needed)

**Action:**
```bash
cd backend && mvn test -q 2>&1
```

**Known pre-existing failures** (unrelated to this upgrade — do not block on these):
- `SchemaControllerIntegrationTest` — pre-existing
- `McpIntegrationTest` — pre-existing
- `PlanServiceTest` — pre-existing

**Expected:** All other tests pass. No new test failures introduced by the upgrade.

If NEW failures appear (not in the known pre-existing list):
- Check if they're related to API changes (e.g., Spring Security test changes, Micrometer metric changes)
- Investigate and fix before proceeding

---

## Batch 5: Runtime Verification

### Task 5.1: Start application + verify zero FeatureDeprecationWarning

**File:** none (verification only)
**Depends:** 4.1

**Action:**
```bash
# Start the app
cd backend && mvn spring-boot:run &
sleep 30

# Load code graph (triggers Neo4j relationship queries)
source .venv/bin/activate && python3 scripts/api.py GET /api/graph/load 2>&1

# Check logs for deprecated id() warnings
pkill -f "spring-boot:run" 2>/dev/null || true
# Search the startup log for FeatureDeprecationWarning
# Should be zero matches
```

**Expected result:** Zero `FeatureDeprecationWarning` entries for `id()` function. SDN 7.3+ (shipped with Boot 3.3.13) uses `elementId()` instead.

---

## Rollback Plan

If the upgrade causes unresolvable issues:

```bash
# Reset pom.xml to original
git checkout backend/pom.xml
```

**Known safe fallback:** Keep Boot 3.2.0 + SDN 7.2.0 with `@Id` String migration from Phase 1. The `id()` deprecation warning is cosmetic — it doesn't affect functionality. Phase 1 alone eliminates the Long ID dependency; Phase 2 eliminates the warning.

---

## Summary of Changes

| Change | File | Status |
|--------|------|--------|
| Parent version 3.2.0 → 3.3.13 | `backend/pom.xml` line 16 | ✅ Always |
| Remove explicit micrometer 1.12.0 version | `backend/pom.xml` lines 76-80 | ✅ Always |
| Spring AI exclusion/removal (if compile fails) | `backend/pom.xml` lines 169-179 | ⚠️ Conditional |
| Java code changes | None | ✅ Zero needed |

### Pre-upgrade checklist
- [ ] Phase 1 (`@Id` String migration) is already merged — 5 entities + 5 repos using String IDs
- [ ] `mvn compile -q` passes on current 3.2.0 — baseline established
- [ ] `mvn test -q` results recorded — known failures documented
- [ ] Neo4j running locally (for runtime verification)

### Post-upgrade checklist
- [ ] `mvn compile -q` passes
- [ ] `mvn test -q` passes (no new failures)
- [ ] App starts without errors
- [ ] `FeatureDeprecationWarning` for `id()` = zero matches
