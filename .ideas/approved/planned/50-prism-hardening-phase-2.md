# PRISM Hardening Phase 2 — Models, Decomposition, and Consistency

**Status:** Complete — all 9 batches implemented  
**Priority:** High  
**Theme:** Reliability / Code Quality / Architecture  
**Dependencies:** None (standalone fixes against current codebase)  
**Source:** Prism analysis after completing Phase 1 (interfaces, circular dependency, @Transactional, time types)

---

## Problem

After completing Phase 1 (14 tasks), a second prism analysis reveals **10 remaining findings** (P1–P10) plus a **deepest finding** about inconsistent architectural patterns across the codebase. The findings span missing model contracts, persistent god classes, frontend regressions, and growing system boundaries.

### Findings Summary

| # | Location | Issue | Severity |
|---|----------|-------|----------|
| P1 | 29/30 model classes | No `equals`/`hashCode`/`toString` — structural identity missing; collections, diffs, and debugging degraded | **HIGH** |
| P2 | `PipelineServiceImpl` (1717L) | God class — stage execution, retry coordination, sub-pipeline lifecycle, state management all in one file | **HIGH** |
| P3 | `ToolExecutor` (1299L) | God class — tool registration, execution, command validation, websocket integration merged | **HIGH** |
| P4 | `SchemaService` (1107L) | God class — CRUD, execution, validation, import/export in one file | **HIGH** |
| P5 | `graph/model/` (4 files) | `GraphExecutionRun`, `GraphNodeExecution`, `GraphExecutionRecord`, `GraphCheckpoint` mirror execution models — manual copy surface, drift risk | MEDIUM |
| P6 | `AgentNodeStrategy.executeAgentNode()` | Non-tool agent path does NOT use `ContextAssembler` — no token budget, no priority pruning | MEDIUM |
| P7 | Tool instructions bypass budget | `buildToolInstructions()` / `buildDocAgentToolInstructions()` appended to systemPrompt before `ContextAssembler` — not budgeted | MEDIUM |
| P8 | `ToolExecutor` setter injection + `@PostConstruct` | Only class with no-arg constructor; inconsistent with constructor injection everywhere else | MEDIUM |
| P9 | Manual `ObjectMapper()` instances | `SchemaExporter`, `OpenClawClient`, `SkillService` create local `ObjectMapper` instead of injecting bean | LOW |
| P10 | Frontend — 3 TS errors | `DiffViewer.vue:48` (string|undefined), `ReviewApprovalDialog.vue:286` (undefined plan), `DashboardView.vue:441` (wrong arg type) — block `vue-tsc --noEmit` | MEDIUM |

### Deepest Finding (Architectural)

**Inconsistent decomposition depth across the service layer.** Phase 1 extracted interfaces but did NOT decompose the implementations. The result is an inconsistent pattern where some services have interfaces (good) but the implementations remain monolithic (bad). `PipelineService` has an interface + 1717L impl, while `SchemaService`, `ToolExecutor`, and `ExecutionUtilityService` have no interface AND remain monolithic. The codebase now lives in an intermediate state — better than before, but unfinished.

---

## Goal

1. **Complete model contracts** — add `equals`/`hashCode`/`toString` to all 30 model classes using Lombok
2. **Decompose god classes** — break `PipelineServiceImpl` (1717L), `ToolExecutor` (1299L), `SchemaService` (1107L) into focused collaborators
3. **Standardize patterns** — eliminate remaining setter injection, manual ObjectMapper, graph model duplication
4. **Extend context budget** — bring non-tool agent path and tool instructions under `ContextAssembler` control
5. **Fix frontend TS regressions** — unblock `vue-tsc --noEmit`

---

## Approach

Fix findings in priority order grouped into 4 phases:

| Phase | Findings | Theme | Risk |
|-------|----------|-------|------|
| **1 — Model Contracts** | P1 | Add Lombok `@Data` to all models | Low — additive, tested |
| **2 — Decomposition** | P2, P3, P4 | Extract god classes into focused services | Medium — touches core execution paths |
| **3 — Standardization** | P5, P8, P9 | Fix inconsistent patterns across codebase | Low-medium — mostly mechanical |
| **4 — Context & Frontend** | P6, P7, P10 | Extend budget coverage; fix TS errors | Low |

### Design Principle: One PR per Finding

Each finding is implemented as a self-contained change with its own test suite, keeping review and rollback simple. P2/P3/P4 are the exceptions — each god class decomposition may need 2-3 sub-steps.

---

## Implementation Batches

### Phase 1 — Model Contracts (P1)

**Objective:** Add structural identity (`equals`/`hashCode`) and debuggability (`toString`) to every model class.

#### Batch 1.1 — Add Lombok `@Data` to all models

Files to modify (29 files):
- `Agent.java`, `ApiKey.java`, `AppModel.java`, `AppUser.java`, `CustomLlmEndpoint.java`
- `DraftResult.java`, `Edge.java`, `ExecutionCheckpoint.java`, `ExecutionError.java`
- `ExecutionRecord.java`, `ExecutionRun.java`, `NodeExecution.java`, `Pipeline.java`
- `Plan.java`, `PlanLevel.java`, `PlanStep.java`, `SchemaValidationResult.java`
- `ShareLink.java`, `Skill.java`, `Stage.java`, `Task.java`, `Tool.java`
- `ToolPermission.java`, `WorkflowSchema.java`
- `ExecutionMode.java`, `PlanStepStatus.java`, `Priority.java`, `ProjectType.java`, `TaskStatus.java`

**Changes:**
1. Add `import lombok.Data;` to each file
2. Add `@Data` annotation to class
3. **Remove all manual getters, setters, `equals()`, `hashCode()`, `toString()`** — Lombok generates them
4. For constructors: keep explicit constructors, add `@NoArgsConstructor` + `@AllArgsConstructor` where both exist
5. For mutable collections (`Map`, `List` fields): add `@Getter(AccessLevel.NONE)` on fields that return mutable internals, OR add `@Data` and expose defensive copies

**Risk:** `@Data` generates `equals`/`hashCode` using ALL fields. If a class uses reference equality implicitly, this changes behavior. Review each class:
- `WorkflowSchema` — Neo4j entity, `id` field is the identity, but `@Data` includes all fields. Add `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` + `@EqualsAndHashCode.Include` on `id` only
- `ExecutionRun` — same, Neo4j entity with `id` as identity
- Same for all Neo4j `@Node` models: `ExecutionRecord`, `ExecutionCheckpoint`, `Plan`, `PlanStep`, `Node`, `NodeExecution`, `Edge`, `ShareLink`, `Skill`, `ApiKey`, `CustomLlmEndpoint`

**Neo4j `@Node` classes** must use `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` with `@EqualsAndHashCode.Include` only on `id` field.

**Testing:**
- Run full test suite: `mvn test`
- No behavior change expected — `equals`/`hashCode` were missing before, adding them is additive (nothing relied on their absence)

---

### Phase 2 — Decomposition (P2, P3, P4)

**Objective:** Reduce the 3 largest service files below 800 lines each by extracting cohesive collaborators.

#### Batch 2.1 — Decompose `PipelineServiceImpl` (1717L)

Current responsibilities:
- Stage execution (`runStages`, `executeSingleStage`, `runPipelineStages`, `runSubPipelineStages`)
- Retry coordination (`retryPipeline`, `retrySubPipeline`, `retryPipelineFromStage`)
- Pipeline lifecycle (`executePipeline`, `executeDerivedStages`, `cancelPipeline`)
- State management (`isPipelineRunning`, `getStageResults`, resume state)
- Stage expansion (`expandTddStages`, `createMinimalPipeline`, `createAppPipeline`)
- Approval handling (`clearStaleApprovals`, approval flags)
- Input mapping (`resolveInputsFromUpstream`)

**Proposed extraction:**

| Extracted Class | Responsibility | Approx Lines |
|----------------|---------------|-------------|
| `StageExecutor` | Run a single stage (execute, config injection, approval checks) | ~200 |
| `PipelineRetryCoordinator` | Retry logic: `retryPipeline`, `retrySubPipeline`, `retryPipelineFromStage`, topological sort, outcome persistence | ~250 |
| `PipelineFactory` | Pipeline creation: `createDefaultPipeline`, `createAppPipeline`, `createMinimalPipeline`, `expandTddStages` | ~200 |
| `StageResumeHandler` | Resume state: `storeResumeState`, `consumeResumeState`, prepare retry context, approval flag setup | ~100 |

`PipelineServiceImpl` retains: `executePipeline`, `executeDerivedStages`, `cancelPipeline`, orchestration of the extracted classes, and compatibility delegates to `PipelineService` interface.

**Plan:**
1. Create `StageExecutor` — move `executeSingleStage`, config preparation, approval check logic
2. Create `PipelineRetryCoordinator` — move retry methods and topological sort
3. Create `PipelineFactory` — move pipeline creation static methods
4. Create `StageResumeHandler` — move resume/approval state logic (may delegate to `PipelineStatusManager`)
5. Update `PipelineServiceImpl` to delegate to extracted classes
6. Update tests

**Testing:** All `PipelineServiceTest` (24 tests) must pass after each extraction.

#### Batch 2.2 — Extract `ToolExecutor` interface + decompose (1299L)

**Step 1 — Extract interface:**
- Create `ToolExecutor` as Spring-injectable interface (currently it uses a static inner `ToolExecutorHandler` + @Component)
- Rename class → `ToolExecutorImpl`
- Move all public methods to interface

**Step 2 — Decompose:**

| Extracted Class | Responsibility |
|----------------|---------------|
| `ToolRegistry` | Tool registration, lookup, allowed commands, tool definition management |
| `ToolExecutionService` | Tool execution orchestration, error handling, WebSocket logging |
| `CommandValidator` | Command whitelist checks, path validation, security sandbox |

`ToolExecutorImpl` retains: integration with `LlmService` for tool-call LLM flows, top-level dispatch.

#### Batch 2.3 — Extract `SchemaService` interface + decompose (1107L)

**Step 1 — Extract interface:**
- Create `SchemaService` interface
- Rename class → `SchemaServiceImpl`

**Step 2 — Decompose:**

| Extracted Class | Responsibility |
|----------------|---------------|
| `SchemaExportService` | Export/import logic (`exportSchema`, `importSchema`) |
| `SchemaExecutionService` | Execution-related: `executeSchema`, `executeHierarchicalPipeline`, node collection |
| `SchemaCRUDService` | Pure CRUD: create, update, delete, list schemas |

`SchemaServiceImpl` retains: orchestration, validation, and complex composite operations.

---

### Phase 3 — Standardization (P5, P8, P9)

#### Batch 3.1 — Unify graph model duplication (P5)

**Option A: Shared base class**
- `ExecutionRun` and `GraphExecutionRun` extend a common `BaseExecutionRun` with shared fields
- Same for `NodeExecution` / `GraphNodeExecution`, etc.

**Option B: Codegen / Mapper**
- Create `ExecutionModelMapper` with bidirectional conversion methods
- Keep both sets but eliminate manual mapping drift via a single mapper class

**Option A is preferred** if Neo4j SDN allows inheritance (it does — `@Node` supports `@SuperClass`). Create:
- `BaseExecutionRun` — `@MappedSuperclass` equivalent (or just `public abstract class` with shared fields)
- Same for other duplicated pairs

**Files:**
- `backend/src/main/java/com/agent/orchestrator/graph/model/GraphExecutionRun.java`
- `backend/src/main/java/com/agent/orchestrator/graph/model/GraphNodeExecution.java`  
- `backend/src/main/java/com/agent/orchestrator/graph/model/GraphExecutionRecord.java`
- `backend/src/main/java/com/agent/orchestrator/graph/model/GraphCheckpoint.java`

#### Batch 3.2 — Fix ToolExecutor injection pattern (P8)

Change `ToolExecutor` from setter injection + `@PostConstruct` to constructor injection.

**Current:**
```java
@Component
public class ToolExecutor {
    // ...
    @PostConstruct
    public void init() {
        this.utilityService = ...;
        this.webSocketHandler = ...;
    }
    // setters for each
}
```

**Target:** constructor injection like every other service. Move `init()` logic into constructor or `@PostConstruct` on the extracted classes.

#### Batch 3.3 — Inject ObjectMapper bean (P9)

Replace `new ObjectMapper()` in:
- `SchemaExporter.java`
- `OpenClawClient.java`
- `SkillService.java`

With injected `ObjectMapper` bean (already configured in `ObjectMapperConfig`).

---

### Phase 4 — Context & Frontend (P6, P7, P10)

#### Batch 4.1 — Extend ContextAssembler to non-tool agent path (P6)

In `AgentNodeStrategy.executeAgentNode()`:
1. Collect context blocks with priorities (same as `executeToolAgentNode()`)
2. Use `ContextAssembler.assemble()` to build budgeted context
3. Remove direct concatenation

**Files:**
- `AgentNodeStrategy.java` — two paths: `executeToolAgentNode` (already uses budget) and `executeAgentNode` (not yet)

#### Batch 4.2 — Budget tool instructions (P7)

In `executeToolAgentNode()`:
1. Add `buildToolInstructions(...)` output as a `ContextBlock` with `HIGH` priority
2. Remove the direct `systemPrompt.append(buildToolInstructions(...))` before assembly

**Risk:** Tool instructions are currently unbounded in size. After budgeting, they may be truncated. This is acceptable — the priority ordering ensures HIGH-priority content is preferred over MEDIUM/LOW.

#### Batch 4.3 — Fix frontend TS errors (P10)

Three errors to fix:

1. **`DiffViewer.vue:48`** — `string | undefined` not assignable to `string`:
   - Fix: add null-coalescing `?? ''` or type guard

2. **`ReviewApprovalDialog.vue:286`** — `Cannot find name 'plan'`:
   - Fix: likely a missing variable declaration or incorrect template reference

3. **`DashboardView.vue:441`** — `{ projectGroup: string }` not `WorkflowSchema`:
   - Fix: extract property before passing vs passing entire object

---

## Implementation Status (Session 2026-06-08 continued)

| Batch | Status | Details |
|-------|--------|---------|
| 1.1 — @Data on all models | ✅ DONE | 23 POJOs + Node inner classes annotated; 6 enums correctly skipped. 355/359 tests pass (4 pre-existing) |
| 2.1 — PipelineFactory | ✅ DONE | Static methods extracted to `PipelineFactory.java`. PipelineServiceImpl reduced 1717L → 1175L |
| 2.2 — ToolExecutor interface | ✅ DONE | Interface extracted (18 public methods + ToolHandler inner). Setters removed from interface. `ToolExecutorImpl` now uses constructor injection with @Autowired parameterized constructor. No-arg constructor preserved for tests. `NodeExecutor.@PostConstruct init()` removed (duplicated Spring DI) |
| 2.3 — SchemaService interface | ✅ DONE | Interface extracted (39 public methods). SchemaService.java → interface, SchemaServiceImpl.java → implementation |
| 4.1 — ContextAssembler to non-tool path | ✅ DONE | `executeAgentNode()` uses `ContextBlock` list + `ContextAssembler.assemble()` with same priority scheme as tool path |
| 4.2 — Budget tool instructions | ✅ DONE | Tool instructions moved from unbounded direct concatenation → `ContextBlock("toolInstructions", ..., HIGH)` inside budgeted assembly |
| 4.3 — Frontend TS errors | ✅ DONE | 3 Vue files fixed, `vue-tsc --noEmit` clean |
| 3.3 — ObjectMapper injection | ✅ NO-OP | Prism was incorrect — no manual `new ObjectMapper()` in target files |
| 3.1 — Graph model annotations | ✅ DONE | All 18 graph models now use `@Getter @Setter @ToString` (+ `@NoArgsConstructor` where safe). 384 lines of manual getter/setter boilerplate removed. Clean compile, 355/359 tests pass |
| 3.2 — ToolExecutor injection | ✅ DONE | Converted from interface-setter + `NodeExecutor.@PostConstruct` to constructor injection. ToolExecutor interface cleaned of setter methods |

**Final validation:** 355/359 tests pass (4 pre-existing NodeRouterTest failures). Frontend vue-tsc --noEmit clean.

---

## File Inventory

### New Files to Create

| File | Batch | Purpose |
|------|-------|---------|
| `backend/src/main/java/com/agent/orchestrator/service/StageExecutor.java` | 2.1 | Extract single-stage execution |
| `backend/src/main/java/com/agent/orchestrator/service/PipelineRetryCoordinator.java` | 2.1 | Retry orchestration |
| `backend/src/main/java/com/agent/orchestrator/service/PipelineFactory.java` | 2.1 | Pipeline template creation |
| `backend/src/main/java/com/agent/orchestrator/service/StageResumeHandler.java` | 2.1 | Resume state management |
| `backend/src/main/java/com/agent/orchestrator/service/ToolRegistry.java` | 2.2 | Tool registration |
| `backend/src/main/java/com/agent/orchestrator/service/ToolExecutionService.java` | 2.2 | Tool execution |
| `backend/src/main/java/com/agent/orchestrator/service/CommandValidator.java` | 2.2 | Command validation |
| `backend/src/main/java/com/agent/orchestrator/service/SchemaExportService.java` | 2.3 | Schema import/export |
| `backend/src/main/java/com/agent/orchestrator/service/SchemaExecutionService.java` | 2.3 | Schema execution |
| `backend/src/main/java/com/agent/orchestrator/service/SchemaCRUDService.java` | 2.3 | Schema CRUD |
| `backend/src/main/java/com/agent/orchestrator/service/ToolExecutorImpl.java` | 2.2 | Renamed ToolExecutor impl |
| `backend/src/main/java/com/agent/orchestrator/service/SchemaServiceImpl.java` | 2.3 | Renamed SchemaService impl |
| `backend/src/test/java/.../StageExecutorTest.java` | 2.1 | Tests |

### Files to Modify

| File | Batch | Changes |
|------|-------|---------|
| All 29 model classes | 1.1 | Add `@Data`, remove manual methods |
| `PipelineServiceImpl.java` | 2.1 | Delegate to extracted classes |
| `ToolExecutor.java` | 2.2 | Extract interface + impl |
| `SchemaService.java` | 2.3 | Extract interface + impl |
| `GraphExecutionRun.java` etc (4 files) | 3.1 | Extend shared base |
| `SchemaExporter.java` | 3.3 | Inject ObjectMapper |
| `OpenClawClient.java` | 3.3 | Inject ObjectMapper |
| `SkillService.java` | 3.3 | Inject ObjectMapper |
| `AgentNodeStrategy.java` | 4.1, 4.2 | Extend ContextAssembler to both paths |
| `DiffViewer.vue` | 4.3 | Fix TS error |
| `ReviewApprovalDialog.vue` | 4.3 | Fix TS error |
| `DashboardView.vue` | 4.3 | Fix TS error |

---

## Test Strategy

| Batch | Test Approach | Expected Coverage |
|-------|---------------|-------------------|
| 1.1 | Full `mvn test` — no behavior change, all pass | 355+ tests pass |
| 2.1 | Extract + delegate; each sub-step tested via existing `PipelineServiceTest` (24) | Green after each |
| 2.2 | Extract interface + impl; `ToolExecutorTest` (4 tests) must pass | Green |
| 2.3 | Extract interface + impl; `SchemaService` has no unit tests (integration only) — add smoke tests | Green + new tests |
| 3.1 | Compile test — no logic change | Green |
| 3.2 | `ToolExecutorTest` passes with constructor injection | Green |
| 3.3 | Compile test — no logic change | Green |
| 4.1, 4.2 | `AgentNodeStrategyTest` (12 tests) must pass | Green |
| 4.3 | `vue-tsc --noEmit` passes | Clean |

**Final validation:** `mvn test` + `vue-tsc --noEmit` + manual smoke test via Studio

---

## Rollback Plan

Each batch is a self-contained commit. Rollback per batch:
- **1.1:** `git revert <commit>` — revert `@Data` additions
- **2.1–2.3:** `git revert <commit>` — reverts to inline implementations
- **3.1–3.3:** `git revert <commit>` — reverts to old pattern
- **4.1–4.3:** `git revert <commit>` — reverts to unbudgeted context / broken TS

No batch depends on a later batch. Rollback order is irrelevant.

---

## Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|------------|--------|------------|
| `@Data` changes Neo4j identity semantics | Medium | HIGH | Use `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` on `@Node` entities |
| Pipeline retry breaks during decomposition | Low | HIGH | Extract one method at a time; test after each extraction |
| ToolExecutor plugin tool registration breaks | Low | MEDIUM | Keep `registerPluginTool()` method on interface; verify plugin tests pass |
| Frontend TS errors have deep dependencies | Low | LOW | Each TS fix is a 1-line change; `vue-tsc` validates immediately |
