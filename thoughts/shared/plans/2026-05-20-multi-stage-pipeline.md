# 2026-05-20: Multi-Stage Pipeline

## Goal

Add a first-class Pipeline abstraction to Axolotl schemas, allowing users to define workflows as ordered stages (source → review → agent → verify → output) instead of raw DAG nodes.

## Implementation

### Backend

- **`Pipeline.java`** — model with `id`, `name`, `stages`, `config`, `parallelStrategy`
- **`Stage.java`** — model with `id`, `name`, `nodeType`, `model`, `systemPrompt`, `userPrompt`, `config`, `dependencies`, `inputMapping`, `outputMapping`, loop/retry config, UI position
- **`WorkflowSchema.java`** — added `pipeline` field (nullable)
- **`PipelineService.java`** — service with:
  - `buildPipelineNodes()` — generates nodes/edges from pipeline stages
  - `executePipeline()` — runs pipeline stages in dependency order via topological sort
  - `runPipelineStages()` — executes stages level by level, parallel within each level
  - `topologicalSortStages()` — Kahn's algorithm on stages
  - `stageToScratchNode()` — creates scratch nodes for stages without pre-existing nodes
  - `createDefaultPipeline()` — creates the standard 5-stage pipeline (receive → review → agent → verify → output)
- **`AgentController.java`** — endpoints:
  - `POST /api/schemas/{id}/pipeline/build`
  - `POST /api/schemas/{id}/pipeline/execute`
  - `POST /api/schemas/{id}/pipeline/cancel`
  - `GET /api/schemas/{id}/pipeline/status`
  - `POST /api/schemas/{id}/pipeline/default`

### Frontend

- **`pipeline.ts`** — TypeScript types for `Pipeline`, `Stage`, `PipelineStatus`
- **`WorkflowSchema`** — added `pipeline?: Pipeline` field
- **`schemaStore.ts`** — added pipeline state (`pipelineStatus`, `pipelineExpanded`) and actions (`buildPipelineNodes`, `executePipeline`, `cancelPipelineExecution`, `refreshPipelineStatus`, `createDefaultPipeline`, `setPipeline`)
- **`PipelinePanel.vue`** — pipeline sidebar component showing stages in levels (topological), with buttons to execute/cancel/build/create default
- **`StudioView.vue`** — added collapsible pipeline sidebar toggle button with 320px sidebar panel

## Usage

1. Create a schema (or use Quick Start)
2. Click "Pipeline" toolbar button in Studio to open pipeline sidebar
3. Click "+ Create Default Pipeline" to generate the 5-stage pipeline
4. Click "▶ Execute Pipeline" to run stages in dependency order
5. Click "Build Nodes" to materialize stages as canvas nodes

## Completed

- **`Pipeline.java`, `Stage.java`** — models with id, name, nodeType, model, prompts, config, dependencies, inputMapping/outputMapping, loop/retry config, UI position
- **`WorkflowSchema.java`** — added `pipeline` field (nullable)
- **`PipelineService.java`** — core orchestration: `buildPipelineNodes()`, `executePipeline()`, `runPipelineStages()`, `topologicalSortStages()`, `stageToScratchNode()`, `createDefaultPipeline()`, `createDecompositionPipeline()`
- **Pipeline stage persistence** — `stageStatus` and `stageOutputs` on `GraphExecutionRun`/`ExecutionRun`; `ExecutionRepository` Cypher for update/read; Neo4j persist on every stage transition
- **Pipeline retry from failure** — `retryPipeline()` creates child `ExecutionRun` with `resumesFrom`, resets failed+dependent stages to pending, re-executes only those
- **Cross-stage artifact passing** — `resolveInputMappings()` and `storeStageResult()` with JSON field extraction (dot-notation), persistence to Neo4j, pre-population on retry/resume
- **PipelineService unit tests** — 16 tests covering `createDefaultPipeline` (5), `buildPipelineNodes` (4), `executePipeline` (3), edge cases (4)
- **`AgentController.java`** — endpoints: build, execute, cancel, status, default, retry
- **`PipelinePanel.vue`** — sidebar component with stage list, Build/Execute/Cancel/Retry buttons, per-stage status
- **`pipeline.ts`** — TypeScript types
- **`schemaStore.ts`** — pipeline state and actions
- **`StudioView.vue`** — collapsible pipeline sidebar toggle with 320px panel

## TDD Test-First Mode (Not Yet Implemented)

### Backend Tasks

#### Batch 1: Pipeline model + config
- Add `tddEnabled: boolean` field to `Pipeline.java` (default: true for new pipelines)
- Add `tddEnabled` to frontend `pipeline.ts` type
- Add `tddEnabled` to `createDefaultPipeline()` factory method

#### Batch 2: Pipeline expansion logic
- Modify `createDecompositionPipeline()` in `PipelineService.java`:
  - When `tddEnabled = true`, expand each branch from 2 stages to 4:
    - `test-X` (agent, `nodeType: "agent"`, prompt: "Write tests for [description] using the project's test framework")
    - `verify-test-X` (verifier, `nodeType: "verifier"`, expects FAIL outcome)
    - `impl-X` (agent, receives test files + failure output as context)
    - `verify-X` (verifier, expects PASS outcome)
  - Dependency wiring:
    - `test-X`: deps = branch's upstream deps
    - `verify-test-X`: deps = [`test-X`]
    - `impl-X`: deps = [`test-X`]
    - `verify-X`: deps = [`impl-X`]
  - When `tddEnabled = false`, expand to 2 stages as before (impl-X → verify-X)

#### Batch 3: Review node prompt injection
- Inject `tddEnabled` flag into Review node's system prompt as part of stage context
- No output format change — the same branch `description` serves both test and impl prompts
- The Review node writes specs at a granularity suitable for test-first when TDD is on

#### Batch 4: Test framework discovery
- The `test-X` agent stage receives scaffold directory path via stage config
- Agent uses `directory_read` / `file_read` tools to inspect project files
- No explicit framework config needed — LLM discovers from scaffold (package.json → jest, build.gradle.kts → JUnit, etc.)

### Frontend Tasks

#### Batch 5: PipelinePanel toggle
- Add "Test-first mode" toggle switch to `PipelinePanel.vue`
- Toggle sets `schema.pipeline.tddEnabled` via store action
- Visual indicator when TDD is active (e.g. badge or label change on stage count: "4 stages with tests")

#### Batch 6: Per-stage labels
- When TDD mode, show expanded stage names in stage list:
  - `test-X`, `verify-test-X`, `impl-X`, `verify-X` instead of just `impl-X`, `verify-X`
- Color-coding: test stages in info/blue, verify in green/red based on status

### Tests

#### Batch 7: Backend tests
- `PipelineService` tests for TDD expansion:
  - `tddEnabled=true` produces 4 stages per branch with correct dependencies
  - `tddEnabled=false` produces 2 stages per branch (backward compat)
  - test stage gets agent nodeType, verify-test and verify get verifier nodeType
  - Dependency wiring correct for both modes
  - Mix of branches with different deps works in TDD mode

## Next Steps

- Stage editing UI (reorder, configure model/prompt)
- Input/output mapping editor between stages
- Conditional branching at stage level
- Parallel stage execution within a level (already supported at infrastructure level)
